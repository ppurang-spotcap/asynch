package org.purang.net
package http.ning

//import org.asynchttpclient._
import java.nio.charset.StandardCharsets
import java.util

import org.purang.net.http._
import org.asynchttpclient.{Request => ARequest, Response => AResponse, _}
import java.util.{List => JUL}
import java.util.concurrent.{ExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scalaz._

object `package` {
  implicit val defaultNonBlockingExecutor = DefaultAsyncHttpClientNonBlockingExecutor()
}

case class DefaultThreadFactory(namePrefix: String = "org.purang.net.http.ning.pool") extends ThreadFactory {

  val threadNumber = new AtomicInteger(1)

  //based on java.util.concurrent.Executors.DefaultThreadFactory
  val group: ThreadGroup = {
    val s = System.getSecurityManager
    if (s != null) s.getThreadGroup else Thread.currentThread().getThreadGroup
  }

  def newThread(r: Runnable): Thread = {
    val t = new Thread(group, r, s"$namePrefix-${threadNumber.getAndIncrement}")
    debug{
      s"new thread ${t.getName}"
    }
    if (!t.isDaemon)
      t.setDaemon(true)
    if (t.getPriority != Thread.NORM_PRIORITY)
      t.setPriority(Thread.NORM_PRIORITY)
    t
  }
}

abstract class AsyncHttpClientNonBlockingExecutor extends NonBlockingExecutor {
  val client: AsyncHttpClient

  import scalaz.concurrent.Task
  def apply(timeout: Timeout) = (req: Request) => {
    debug {
      s"Thread ${Thread.currentThread().getName}-${Thread.currentThread().getId} asking for a task to be run $req"
    }
    Task.apply({
      var builder = new RequestBuilder(req.method).setUrl(req.url.url)
      for {
        header <- req.headers
        value <- header.values
      } builder = builder.addHeader(header.name, value)

      for {
        content <- req.body
        str <- content
      } builder = builder.setBody(str.getBytes("utf-8"))

      //
      debug{
        s"blocking to execute $req on thread '${Thread.currentThread().getName}'-'${Thread.currentThread().getId}'"
      }
      val response: AResponse = client.executeRequest[AResponse](
        builder.build(),
        (new Handler(): AsyncHandler[AResponse])
      ).get(timeout, TimeUnit.MILLISECONDS)


      import scala.collection.JavaConversions._
      import org.purang.net.http._

      implicit val mapEntryToTuple : java.util.Map.Entry[String,String] => (String, String) = x => x.getKey -> x.getValue
      val headers = response.getHeaders.groupBy(_.getKey).foldLeft(Vector[Header]()) {
        case (headers, (key, iterable)) =>
          val map: util.Collection[String] = iterable.map(_.getValue)
          headers ++ (key `:` collectionAsScalaIterable(map))
      }
      val responseBody: String = response.getResponseBody(StandardCharsets.UTF_8)
      val code: Int = response.getStatusCode()
      debug {
        f"'${Thread.currentThread().getName}'-'${Thread.currentThread().getId}' req: $req  %n resp: %n code: $code %n headers: $headers %n body: $responseBody"
      }

      (code, headers, Option(responseBody), req)
    })
  }

}

class Handler extends AsyncHandler[AResponse]  {
  val builder =
          new AResponse.ResponseBuilder()

  def onBodyPartReceived(content: HttpResponseBodyPart): AsyncHandler.State = {
      builder.accumulate(content)
    AsyncHandler.State.CONTINUE
  }

  def onStatusReceived(status: HttpResponseStatus): AsyncHandler.State = {
      builder.accumulate(status)
    AsyncHandler.State.CONTINUE
  }

  def onHeadersReceived( headers: HttpResponseHeaders) : AsyncHandler.State = {
      builder.accumulate(headers)
    AsyncHandler.State.CONTINUE
  }

  def onCompleted(): AResponse  = {
    builder.build()
  }

  def onThrowable(t: Throwable) {
    throw t
  }
}

case class DefaultAsyncHttpClientNonBlockingExecutor( config : AsyncHttpClientConfig = {
 new DefaultAsyncHttpClientConfig.Builder()
    .setCompressionEnforced(true)
    .setConnectTimeout(500)
    .setRequestTimeout(3000)
    .build()
})
  extends ConfiguredAsyncHttpClientExecutor {

  def close() = {
    this.client.close()
  }
}

trait ConfiguredAsyncHttpClientExecutor extends AsyncHttpClientNonBlockingExecutor {
  val config: AsyncHttpClientConfig
  lazy val client: AsyncHttpClient = new DefaultAsyncHttpClient(config)
}