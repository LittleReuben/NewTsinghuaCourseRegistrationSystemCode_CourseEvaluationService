package Impl


import Objects.SemesterPhaseService.Phase
import Utils.CourseEvaluationProcess.validateStudentToken
import Objects.SemesterPhaseService.SemesterPhase
import Objects.UserAccountService.SafeUserInfo
import Utils.CourseEvaluationProcess.recordCourseEvaluationOperationLog
import APIs.UserAuthService.VerifyTokenValidityMessage
import APIs.UserAccountService.QuerySafeUserInfoByTokenMessage
import APIs.SemesterPhaseService.QuerySemesterPhaseStatusMessage
import Objects.SystemLogService.SystemLogEntry
import Objects.CourseEvaluationService.Rating
import Objects.UserAccountService.UserRole
import Objects.SemesterPhaseService.Permissions
import Common.API.{PlanContext, Planner}
import Common.DBAPI._
import Common.Object.SqlParameter
import Common.ServiceUtils.schemaName
import cats.effect.IO
import org.slf4j.LoggerFactory
import org.joda.time.DateTime
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.implicits.*
import Common.Serialize.CustomColumnTypes.{decodeDateTime, encodeDateTime}
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import org.joda.time.DateTime
import cats.implicits.*
import Common.DBAPI._
import Common.API.{PlanContext, Planner}
import cats.effect.IO
import Common.Object.SqlParameter
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}
import Common.ServiceUtils.schemaName
import Objects.SemesterPhaseService.Permissions
import Objects.SemesterPhaseService.{Permissions, SemesterPhase}
import Utils.CourseEvaluationProcess.{validateStudentToken, recordCourseEvaluationOperationLog}
import cats.implicits._
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}

case class UpdateCourseEvaluationMessagePlanner(
  studentToken: String,
  courseID: Int,
  newRating: Rating,
  newFeedback: String,
  override val planContext: PlanContext
) extends Planner[String] {
  val logger = LoggerFactory.getLogger(this.getClass.getSimpleName + "_" + planContext.traceID.id)

  override def plan(using PlanContext): IO[String] = {
    for {
      // Step 1: 验证学生的身份令牌
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 验证 studentToken: ${studentToken}"))
      studentIDOpt <- validateStudentToken(studentToken)
      studentID <- studentIDOpt match {
        case None =>
          IO {
            logger.info(s"[UpdateCourseEvaluation] Token验证失败")
          } *> IO.raiseError(new IllegalStateException("Token验证失败。"))
        case Some(id) => IO.pure(id)
      }

      // Step 2: 检查当前学期阶段权限
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 检查当前学期阶段权限"))
      semesterPhase <- QuerySemesterPhaseStatusMessage(studentToken).send
      permissions = semesterPhase.permissions
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 当前阶段的权限配置: ${permissions}"))
      _ <- if (!permissions.allowStudentEvaluate) {
        IO(logger.info(s"[UpdateCourseEvaluation] 评价权限未开启！")) *> 
        IO.raiseError(new IllegalStateException("评价权限未开启！"))
      } else IO.unit

      // Step 3: 确认学生是否曾评价该课程
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 确认学生是否曾评价该课程, studentID: ${studentID}, courseID: ${courseID}"))
      existingEvaluationOpt <- findExistingEvaluation(studentID, courseID)
      evaluationID <- existingEvaluationOpt match {
        case None =>
          IO {
            logger.info(s"[UpdateCourseEvaluation] 学生未曾评价该课程")
          } *> IO.raiseError(new IllegalStateException("学生未曾评价该课程！"))
        case Some(evaluationID) => IO.pure(evaluationID)
      }

      // Step 4: 更新课程评价
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 开始更新课程评价"))
      updatedEvaluationResult <- updateCourseEvaluation(evaluationID, newRating, newFeedback)
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 更新评价结果: ${updatedEvaluationResult}"))

      // Step 5: 记录操作日志
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 记录操作日志"))
      logDetails = s"Rating: ${newRating}, Feedback: ${newFeedback}"
      _ <- recordCourseEvaluationOperationLog(studentID, "UpdateCourseEvaluation", courseID, logDetails)

      // Step 6: 返回成功消息
      _ <- IO(logger.info(s"[UpdateCourseEvaluation] 更新成功"))
    } yield "更新成功！"
  }

  private def findExistingEvaluation(studentID: Int, courseID: Int)(using PlanContext): IO[Option[Int]] = {
    val sql =
      s"""
      SELECT evaluation_id
      FROM ${schemaName}.course_evaluation_table
      WHERE evaluator_id = ? AND course_id = ?
      """.stripMargin
    val params = List(
      SqlParameter("Int", studentID.toString),
      SqlParameter("Int", courseID.toString)
    )
    readDBJsonOptional(sql, params).map(_.map(json => decodeField[Int](json, "evaluation_id")))
  }

  private def updateCourseEvaluation(evaluationID: Int, rating: Rating, feedback: String)(using PlanContext): IO[String] = {
    val sql =
      s"""
      UPDATE ${schemaName}.course_evaluation_table
      SET rating = ?, feedback = ?
      WHERE evaluation_id = ?
      """.stripMargin
    val params = List(
      SqlParameter("Int", rating.toString),
      SqlParameter("String", feedback),
      SqlParameter("Int", evaluationID.toString),
    )
    writeDB(sql, params)
  }
}