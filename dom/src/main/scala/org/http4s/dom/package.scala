/*
 * Copyright 2021 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js.annotation.JSExport

package object dom {

  implicit def fileEncoder[F[_]](implicit F: Async[F]): EntityEncoder[F, dom.File] =
    blobEncoder.narrow

  implicit def blobEncoder[F[_]](implicit F: Async[F]): EntityEncoder[F, dom.Blob] =
    EntityEncoder.entityBodyEncoder.contramap { blob =>
      readReadableStream[F](F.delay(blob.stream()), cancelAfterUse = true)
    }

  implicit def readableStreamEncoder[F[_]: Async]
      : EntityEncoder[F, dom.ReadableStream[Uint8Array]] =
    EntityEncoder.entityBodyEncoder.contramap { rs =>
      readReadableStream(rs.pure, cancelAfterUse = true)
    }

  private[dom] def fromDomResponse[F[_]](response: dom.Response)(
      implicit F: Async[F]): F[Response[F]] =
    F.fromEither(Status.fromInt(response.status)).map { status =>
      Response[F](
        status = status,
        headers = fromDomHeaders(response.headers),
        body = Stream.fromOption(Option(response.body)).flatMap { rs =>
          readReadableStream[F](rs.pure, cancelAfterUse = true)
        }
      )
    }

  private[dom] def toDomHeaders(headers: Headers): dom.Headers =
    new dom.Headers(
      headers
        .headers
        .view
        .map {
          case Header.Raw(name, value) =>
            name.toString -> value
        }
        .toMap
        .toJSDictionary)

  private[dom] def fromDomHeaders(headers: dom.Headers): Headers =
    Headers(
      headers.map { header => header(0) -> header(1) }.toList
    )

  private def readReadableStream[F[_]](
      readableStream: F[dom.ReadableStream[Uint8Array]],
      cancelAfterUse: Boolean
  )(implicit F: Async[F]): Stream[F, Byte] = {
    def read(readableStream: dom.ReadableStream[Uint8Array]) =
      Stream
        .bracket(F.delay(readableStream.getReader()))(r => F.delay(r.releaseLock()))
        .flatMap { reader =>
          Stream.unfoldChunkEval(reader) { reader =>
            F.fromPromise(F.delay(reader.read())).map { chunk =>
              if (chunk.done)
                None
              else
                Some((fs2.Chunk.uint8Array(chunk.value), reader))
            }
          }
        }

    if (cancelAfterUse)
      Stream.bracketCase(readableStream)(cancelReadableStream(_, _)).flatMap(read(_))
    else
      Stream.eval(readableStream).flatMap(read(_))
  }

  private[dom] def cancelReadableStream[F[_], A](
      rs: dom.ReadableStream[A],
      exitCase: Resource.ExitCase
  )(implicit F: Async[F]): F[Unit] = F.fromPromise {
    F.delay {
      // Best guess: Firefox internally locks a ReadableStream after it is "drained"
      // This checks if the stream is locked before canceling it to avoid an error
      if (!rs.locked) exitCase match {
        case Resource.ExitCase.Succeeded =>
          rs.cancel(js.undefined)
        case Resource.ExitCase.Errored(ex) =>
          rs.cancel(ex.toString())
        case Resource.ExitCase.Canceled =>
          rs.cancel(js.undefined)
      }
      else js.Promise.resolve[Unit](())
    }
  }

  private def toReadableStream[F[_]](
      implicit F: Async[F]): Pipe[F, Byte, dom.ReadableStream[Uint8Array]] =
    (in: Stream[F, Byte]) =>
      Stream.resource(Dispatcher.sequential).flatMap { dispatcher =>
        Stream.eval(Queue.synchronous[F, Option[Chunk[Byte]]]).flatMap { chunks =>
          Stream
            .eval {
              F.delay {
                val source = new dom.ReadableStreamUnderlyingSource[Uint8Array] {
                  `type` = dom.ReadableStreamType.bytes
                  pull = js.defined { controller =>
                    dispatcher.unsafeToPromise {
                      chunks.take.flatMap {
                        case Some(chunk) =>
                          F.delay(controller.enqueue(chunk.toUint8Array))
                        case None => F.delay(controller.close())
                      }
                    }
                  }
                }
                dom.ReadableStream[Uint8Array](source)
              }
            }
            .concurrently(in.enqueueNoneTerminatedChunks(chunks))
        }
      }

  private[dom] lazy val supportsRequestStreams = {
    var duplexAccessed = false
    val hasContentType = new dom.Request(
      "http://http4s.org/",
      new AnyRef {
        @JSExport
        val body = dom.ReadableStream()
        @JSExport
        val method = dom.HttpMethod.POST
        @JSExport
        def duplex = {
          duplexAccessed = true
          dom.RequestDuplex.half
        }
      }.asInstanceOf[dom.RequestInit]
    ).headers.has("Content-Type")

    duplexAccessed && !hasContentType
  }

}
