package asura.core.http

import java.nio.charset.StandardCharsets
import java.util
import java.util.Base64

import akka.http.javadsl.model.ContentType
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.util.ByteString
import asura.common.exceptions.WithDataException
import asura.common.util.StringUtils
import asura.core.CoreConfig.materializer
import asura.core.assertion.engine.HttpResponseAssert
import asura.core.concurrent.ExecutionContextManager.sysGlobal
import asura.core.es.model.HttpCaseRequest
import asura.core.runtime._
import asura.core.util.JsonPathUtils
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

object HttpRunner {

  val logger = Logger("HttpRunner")

  def test(docId: String, cs: HttpCaseRequest, context: RuntimeContext = RuntimeContext()): Future[HttpResult] = {
    implicit val metrics = RuntimeMetrics()
    metrics.start()
    context.eraseCurrentData()
    var options = context.options
    if (null != options) {
      options.caseEnv = cs.env
    } else {
      options = ContextOptions(caseEnv = cs.env)
      context.options = options
    }
    metrics.renderRequestStart()
    context.evaluateOptions().flatMap(_ => {
      HttpParser.toHttpRequest(cs, context)
        .flatMap(request => toCaseRequestTuple(request, context))
        .flatMap(tuple => {
          val env = if (null != context.options) context.options.getUsedEnv() else null
          val futureResult = if (null != env && env.enableProxy) {
            metrics.performRequestStart()
            val proxyServer = context.options.getUsedEnv().server
            HttpEngine.singleRequestWithProxy(tuple._1, proxyServer).flatMap(res => {
              Unmarshal(res.entity).to[ByteString].flatMap(resBody => {
                metrics.evalAssertionBegin()
                context.setCurrentMetrics(metrics)
                HttpResponseAssert.generateHttpReport(docId, cs.assert, res,
                  byteStringToString(resBody, res.entity.getContentType()), tuple._2, context
                )
              })
            })
          } else {
            metrics.performRequestStart()
            HttpEngine.singleRequest(tuple._1).flatMap(res => {
              Unmarshal(res.entity).to[ByteString].flatMap(resBody => {
                metrics.evalAssertionBegin()
                context.setCurrentMetrics(metrics)
                HttpResponseAssert.generateHttpReport(docId, cs.assert, res,
                  byteStringToString(resBody, res.entity.getContentType()), tuple._2, context
                )
              })
            })
          }
          futureResult.recover {
            case t: Throwable => throw WithDataException(t, tuple._2)
          }
        })
    }).map(result => {
      metrics.evalAssertionEnd()
      metrics.theEnd()
      result.metrics = metrics.toReportStepItemMetrics()
      result
    })
  }

  def toCaseRequestTuple(req: HttpRequest, context: RuntimeContext): Future[(HttpRequest, HttpRequestReportModel)] = {
    Unmarshal(req.entity).to[String].map(reqBody => {
      val headers = scala.collection.mutable.HashMap[String, String]()
      req.headers.foreach(h => headers += (h.name() -> h.value()))
      val mediaType = req.entity.contentType.mediaType.value
      if (mediaType != "none/none") {
        headers += (HttpContentTypes.KEY_CONTENT_TYPE -> mediaType)
      }
      val reqMap = new util.HashMap[String, Object]()
      context.setCurrentRequest(reqMap)
      if (mediaType == HttpContentTypes.JSON && StringUtils.isNotEmpty(reqBody)) {
        try {
          reqMap.put("body", JsonPathUtils.parse(reqBody))
        } catch {
          case _: Throwable => reqMap.put("body", reqBody)
        }
      } else {
        reqMap.put("body", reqBody)
      }
      (req, HttpRequestReportModel(req.method.value, req.getUri().toString, headers, reqBody))
    })
  }

  // Base64 encode if not text
  private def byteStringToString(byteString: ByteString, contentType: ContentType): String = {
    if (contentType.mediaType.isImage || contentType.mediaType.isVideo) {
      new String(Base64.getEncoder.encode(byteString.toByteBuffer.array()))
    } else {
      byteString.decodeString(StandardCharsets.UTF_8)
    }
  }
}
