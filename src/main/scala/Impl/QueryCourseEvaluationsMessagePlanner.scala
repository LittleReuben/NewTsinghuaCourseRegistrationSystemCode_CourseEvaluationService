package Impl


import APIs.UserAuthService.VerifyTokenValidityMessage
import Objects.CourseEvaluationService.CourseEvaluation
import Objects.CourseEvaluationService.Rating
import Common.API.{PlanContext, Planner}
import Common.DBAPI._
import Common.Object.SqlParameter
import Common.ServiceUtils.schemaName
import cats.effect.IO
import org.slf4j.LoggerFactory
import io.circe.Json
import org.joda.time.DateTime
import cats.implicits._
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
import Objects.CourseEvaluationService.Rating
import Objects.CourseEvaluationService.{CourseEvaluation, Rating}
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import cats.implicits.*
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}

case class QueryCourseEvaluationsMessagePlanner(
                                                  userToken: String,
                                                  courseID: Int,
                                                  override val planContext: PlanContext
                                                ) extends Planner[List[CourseEvaluation]] {

  private val logger = LoggerFactory.getLogger(this.getClass.getSimpleName + "_" + planContext.traceID.id)

  override def plan(using planContext: PlanContext): IO[List[CourseEvaluation]] = {
    for {
      // Step 1: Verify token validity
      _ <- IO(logger.info(s"调用VerifyTokenValidityMessage验证token: $userToken"))
      tokenValid <- VerifyTokenValidityMessage(userToken).send
      _ <- if (!tokenValid) 
             IO.raiseError(new IllegalArgumentException("Token验证失败。"))
           else 
             IO(logger.info("Token验证通过"))

      // Step 2: Query course evaluations from CourseEvaluationTable
      _ <- IO(logger.info(s"从CourseEvaluationTable中查询课程ID为${courseID}的评价数据"))
      evaluations <- getCourseEvaluations(courseID)

      _ <- IO(logger.info(s"查询到${evaluations.size}条评价数据"))
    } yield evaluations
  }

  private def getCourseEvaluations(courseID: Int)(using PlanContext): IO[List[CourseEvaluation]] = {
    val sql =
      s"""
        SELECT evaluator_id, course_id, rating, feedback
        FROM ${schemaName}.course_evaluation_table
        WHERE course_id = ?;
      """
    for {
      _ <- IO(logger.info(s"执行SQL查询语句: ${sql}"))
      rows <- readDBRows(sql, List(SqlParameter("Int", courseID.toString)))
      evaluations <- IO {
        rows.map { row =>
          CourseEvaluation(
            evaluatorID = decodeField[Int](row, "evaluator_id"),
            courseID = decodeField[Int](row, "course_id"),
            rating = Rating.fromString(decodeField[Int](row, "rating").toString),
            feedback = decodeField[Option[String]](row, "feedback")
          )
        }
      }
      _ <- IO(logger.info(s"成功将查询结果封装为CourseEvaluation对象"))
    } yield evaluations
  }
}