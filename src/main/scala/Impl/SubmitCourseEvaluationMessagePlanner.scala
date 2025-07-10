package Impl


import Common.API.{PlanContext, Planner}
import Common.DBAPI._
import Common.Object.SqlParameter
import Common.ServiceUtils.schemaName
import cats.effect.IO
import io.circe._
import Utils.CourseEvaluationProcess._
import Objects.SemesterPhaseService.{Phase, SemesterPhase, Permissions}
import Objects.UserAccountService.SafeUserInfo
import Objects.CourseEvaluationService.Rating
import org.slf4j.LoggerFactory
import org.joda.time.DateTime
import APIs.UserAuthService.VerifyTokenValidityMessage
import APIs.UserAccountService.QuerySafeUserInfoByTokenMessage
import APIs.SemesterPhaseService.QuerySemesterPhaseStatusMessage
import io.circe.syntax._
import io.circe.generic.auto._
import cats.implicits.*
import Common.Serialize.CustomColumnTypes.{decodeDateTime, encodeDateTime}
import Objects.SemesterPhaseService.Phase
import Utils.CourseEvaluationProcess.validateStudentToken
import Objects.SemesterPhaseService.SemesterPhase
import Utils.CourseEvaluationProcess.recordCourseEvaluationOperationLog
import Utils.CourseEvaluationProcess.validateStudentEligibilityForEvaluation
import Objects.SystemLogService.SystemLogEntry
import Objects.UserAccountService.UserRole
import Objects.SemesterPhaseService.Permissions
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
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}

case class SubmitCourseEvaluationMessagePlanner(
    studentToken: String,
    courseID: Int,
    rating: Rating,
    feedback: String,
    override val planContext: PlanContext
) extends Planner[String] {
  val logger = LoggerFactory.getLogger(this.getClass.getSimpleName + "_" + planContext.traceID.id)

  override def plan(using PlanContext): IO[String] = {
    for {
      // Step 1: Validate student token
      _ <- IO(logger.info(s"[Step 1] 验证学生 token: ${studentToken}"))
      studentIDOpt <- validateStudentToken(studentToken)
      studentID <- studentIDOpt match {
        case Some(id) => IO.pure(id)
        case None =>
          IO(logger.info(s"Token 验证失败，studentToken: ${studentToken}")) >>
            IO.raiseError(new IllegalArgumentException("Token验证失败"))
      }

      // Step 2: Get the semester phase and permissions
      _ <- IO(logger.info(s"[Step 2] 获取当前学期阶段权限"))
      semesterPhase <- QuerySemesterPhaseStatusMessage(studentToken).send

      // Step 3: Check if student evaluation is allowed
      _ <- IO(logger.info(s"[Step 3] 检查阶段权限是否允许提交评价"))
      isEvaluationAllowed = semesterPhase.permissions.allowStudentEvaluate
      _ <- if (!isEvaluationAllowed) {
        IO(logger.info(s"评价权限未开启！")) >>
          IO.raiseError(new IllegalArgumentException("评价权限未开启！"))
      } else IO.unit

      // Step 4: Validate student's eligibility for evaluation
      _ <- IO(logger.info(s"[Step 4] 验证学生评价资格，studentID: ${studentID}, courseID: ${courseID}"))
      isEligible <- validateStudentEligibilityForEvaluation(studentID, courseID)
      _ <- if (!isEligible) {
        IO(logger.info(s"学生未选上该课程，无法进行评价")) >>
          IO.raiseError(new IllegalArgumentException("未选上该课程，无法进行评价"))
      } else IO.unit

      // Step 5: Check if the student has already submitted an evaluation
      _ <- IO(logger.info(s"[Step 5] 检查学生是否已评价过该课程"))
      evaluationRecordOpt <- readDBJsonOptional(
        s"""
        SELECT * 
        FROM ${schemaName}.course_evaluation_table 
        WHERE evaluator_id = ? AND course_id = ?;
        """.stripMargin,
        List(
          SqlParameter("Int", studentID.toString),
          SqlParameter("Int", courseID.toString)
        )
      )
      _ <- evaluationRecordOpt match {
        case Some(_) =>
          IO(logger.info(s"学生已评价过课程，studentID: ${studentID}, courseID: ${courseID}")) >>
            IO.raiseError(new IllegalArgumentException("学生已评价过该课程"))
        case None => IO.unit
      }

      // Step 6: Add evaluation record to the database
      _ <- IO(logger.info(s"[Step 6] 添加课程评价记录到数据库"))
      _ <- writeDB(
        s"""
        INSERT INTO ${schemaName}.course_evaluation_table 
        (evaluator_id, course_id, rating, feedback) 
        VALUES (?, ?, ?, ?);
        """.stripMargin,
        List(
          SqlParameter("Int", studentID.toString),
          SqlParameter("Int", courseID.toString),
          SqlParameter("Int", rating.toString), // Rating enum to string
          SqlParameter("String", feedback)
        )
      )

      // Step 7: Record operation log
      _ <- IO(logger.info(s"[Step 7] 记录操作日志"))
      _ <- recordCourseEvaluationOperationLog(
        studentID = studentID,
        operation = "提交评价",
        courseID = courseID,
        details = s"Rating: ${rating}, Feedback: ${feedback}"
      )

      // Step 8: Return result message
      _ <- IO(logger.info(s"[Step 8] 提交成功"))
    } yield "提交成功！"
  }
}