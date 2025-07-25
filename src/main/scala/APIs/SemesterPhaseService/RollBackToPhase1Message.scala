package APIs.SemesterPhaseService

import Common.API.API
import Global.ServiceCenter.SemesterPhaseServiceCode

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.*
import io.circe.parser.*
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}

import com.fasterxml.jackson.core.`type`.TypeReference
import Common.Serialize.JacksonSerializeUtils

import scala.util.Try

import org.joda.time.DateTime
import java.util.UUID


/**
 * RollBackToPhase1Message
 * desc: 清空所有选课信息，保留用户与开课信息
 * @param adminToken: String (管理员token)
 */

case class RollBackToPhase1Message(
  adminToken: String
) extends API[Boolean](SemesterPhaseServiceCode)



case object RollBackToPhase1Message{
    
  import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}

  // Circe 默认的 Encoder 和 Decoder
  private val circeEncoder: Encoder[RollBackToPhase1Message] = deriveEncoder
  private val circeDecoder: Decoder[RollBackToPhase1Message] = deriveDecoder

  // Jackson 对应的 Encoder 和 Decoder
  private val jacksonEncoder: Encoder[RollBackToPhase1Message] = Encoder.instance { currentObj =>
    Json.fromString(JacksonSerializeUtils.serialize(currentObj))
  }

  private val jacksonDecoder: Decoder[RollBackToPhase1Message] = Decoder.instance { cursor =>
    try { Right(JacksonSerializeUtils.deserialize(cursor.value.noSpaces, new TypeReference[RollBackToPhase1Message]() {})) } 
    catch { case e: Throwable => Left(io.circe.DecodingFailure(e.getMessage, cursor.history)) }
  }
  
  // Circe + Jackson 兜底的 Encoder
  given RollBackToPhase1MessageEncoder: Encoder[RollBackToPhase1Message] = Encoder.instance { config =>
    Try(circeEncoder(config)).getOrElse(jacksonEncoder(config))
  }

  // Circe + Jackson 兜底的 Decoder
  given RollBackToPhase1MessageDecoder: Decoder[RollBackToPhase1Message] = Decoder.instance { cursor =>
    circeDecoder.tryDecode(cursor).orElse(jacksonDecoder.tryDecode(cursor))
  }


}

