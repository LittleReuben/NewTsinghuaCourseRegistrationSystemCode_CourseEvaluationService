package Impl


import Common.API.{PlanContext, Planner}
import Common.DBAPI._
import Common.ServiceUtils.schemaName
import Objects.SemesterPhaseService.Phase
import Utils.CourseEvaluationProcess.validateStudentToken
import Objects.SemesterPhaseService.SemesterPhase
import Objects.UserAccountService.SafeUserInfo
import Utils.CourseEvaluationProcess.recordCourseEvaluationOperationLog
import APIs.UserAuthService.VerifyTokenValidityMessage
import APIs.UserAccountService.QuerySafeUserInfoByTokenMessage
import APIs.SemesterPhaseService.QuerySemesterPhaseStatusMessage
import Objects.SystemLogService.SystemLogEntry
import Objects.UserAccountService.UserRole
import Objects.SemesterPhaseService.Permissions
import cats.effect.IO
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.implicits.*
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
import Common.Object.SqlParameter
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}

case class DeleteCourseEvaluationMessagePlanner(
  studentToken: String,
  courseID: Int,
  override val planContext: PlanContext
) extends Planner[String] {
  val logger = LoggerFactory.getLogger(this.getClass.getSimpleName + "_" + planContext.traceID.id)

  override def plan(using PlanContext): IO[String] = {
    for {
      // Step 1: Validate student token
      _ <- IO(logger.info(s"开始验证 studentToken: ${studentToken}"))
      studentIDOpt <- validateStudentToken(studentToken)
      result <- studentIDOpt match {
        case None =>
          IO.raiseError(new IllegalArgumentException("Token验证失败。"))
        case Some(studentID) =>
          processDeleteEvaluation(studentID)
      }
    } yield result
  }

  private def processDeleteEvaluation(studentID: Int)(using PlanContext): IO[String] = {
    for {
      // Step 2: Query semester phase and permissions
      _ <- IO(logger.info(s"查询学期阶段权限"))
      semesterPhase <- QuerySemesterPhaseStatusMessage(studentToken).send

      // Step 3: Check if evaluation deletion is allowed
      allowDelete <- IO(semesterPhase.permissions.allowStudentEvaluate)
      _ <- IO(logger.info(s"允许学生评价课程: ${allowDelete}"))
      result <- if (!allowDelete) {
        IO.raiseError(new IllegalStateException("评价权限未开启！"))
      } else {
        handleEvaluationDeletion(studentID)
      }
    } yield result
  }

  private def handleEvaluationDeletion(studentID: Int)(using PlanContext): IO[String] = {
    for {
      // Step 4: Check if course evaluation exists
      _ <- IO(logger.info(s"查询学生对课程的评价记录"))
      evaluationRecordOpt <- readDBJsonOptional(
        s"""
          SELECT * FROM ${schemaName}.course_evaluation_table
          WHERE evaluator_id = ? AND course_id = ?;
        """,
        List(
          SqlParameter("Int", studentID.toString),
          SqlParameter("Int", courseID.toString)
        )
      )
      result <- evaluationRecordOpt match {
        case None =>
          IO.raiseError(new IllegalStateException("学生未曾评价该课程！"))
        case Some(evaluationRecord) =>
          processEvaluationDeletion(studentID, evaluationRecord)
      }
    } yield result
  }

  private def processEvaluationDeletion(studentID: Int, evaluationRecord: Json)(using PlanContext): IO[String] = {
    for {
      // Step 5: Delete evaluation record
      evaluationID <- IO(decodeField[Int](evaluationRecord, "evaluation_id"))
      _ <- IO(logger.info(s"删除课程评价记录 evaluation_id: ${evaluationID}"))
      _ <- writeDB(
        s"""
          DELETE FROM ${schemaName}.course_evaluation_table
          WHERE evaluation_id = ?;
        """,
        List(
          SqlParameter("Int", evaluationID.toString)
        )
      )

      // Step 6: Record operation log
      _ <- IO(logger.info("记录操作日志"))
      operation <- IO("删除课程评价")
      currentTime <- IO(DateTime.now())
      details <- IO(s"操作时间: ${currentTime}, 删除课程评价记录 evaluationID: ${evaluationID}")
      _ <- recordCourseEvaluationOperationLog(studentID, operation, courseID, details)

      // Step 7: Return success message
      _ <- IO(logger.info("删除课程评价成功"))
    } yield "删除成功！"
  }
}