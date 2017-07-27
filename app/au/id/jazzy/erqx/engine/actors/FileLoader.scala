package au.id.jazzy.erqx.engine.actors

import akka.actor.Actor
import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString

import scala.concurrent.blocking
import play.doc.{PlayDoc, PlayDocTemplates}
import au.id.jazzy.erqx.engine.services.git.{GitFileRepository, GitRepository}
import au.id.jazzy.erqx.engine.models.Blog
import play.api.http.HttpEntity

/**
 * Actor responsible for loading and rendering files
 */
class FileLoader(gitRepository: GitRepository) extends Actor {

  import BlogActor._

  def receive = {

    case LoadContent(blog, path) =>
      sender ! blocking(gitRepository.loadContent(blog.hash, path))

    case LoadStream(blog, path) =>
      sender ! blocking(gitRepository.loadStream(blog.hash, path).map {
        case file if file.isLarge =>
          HttpEntity.Streamed(
            StreamConverters.fromInputStream(file.openStream _),
            Some(file.getSize),
            None
          )
        case smallFile =>
          HttpEntity.Strict(ByteString(smallFile.getCachedBytes), None)
      })

    case RenderPost(blog, post, absoluteUri) =>
      render(blog, post.path, post.format, absoluteUri)

    case RenderPage(blog, page) =>
      render(blog, page.path, page.format, None)
  }

  private def render(blog: Blog, path: String, format: String, absoluteUri: Option[String]) = {
    val rendered = blocking {
      gitRepository.loadContent(blog.hash, path).map { content =>
      // Strip off front matter
        val lines = content.split("\n").dropWhile(_.trim.isEmpty)
        val body = if (lines.headOption.exists(_.trim == "---")) {
          lines.drop(1).dropWhile(_.trim != "---").drop(1).mkString("\n")
        } else content

        format match {
          case "md" =>
            val repo = new GitFileRepository(gitRepository, blog.hash, None)
            new PlayDoc(repo, repo, "", "", None, PlayDocTemplates, None).render(body, None)
          case _ =>
            body
        }
      }
    }
    val uriAdjusted = absoluteUri.flatMap { uri =>
      rendered.map(c => replaceCommonUris(c, uri))
    }.orElse(rendered)

    sender ! uriAdjusted
  }

  private def replaceCommonUris(s: String, uri: String): String = {
    s.replaceAll("href=\"\\./", "href=\"" + uri)
      .replaceAll("href='\\./", "href='" + uri)
      .replaceAll("src=\"\\./", "src=\"" + uri)
      .replaceAll("src='\\./", "src='" + uri)
  }

}
