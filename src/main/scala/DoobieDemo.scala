import cats.effect.{ExitCode, IO, IOApp}
import doobie._
import cats.implicits._
import doobie.util.transactor.Transactor
import doobie.implicits._
import doobie.util.{Get, Put, Read, Write}
import doobie.util.update.Update

import java.util.UUID

object DoobieDemo extends IOApp {

  case class Actor(id: Int, name: String)
  case class Movie(id: String, title: String, year: Int, actors: List[String], director: String)

  implicit class Debugger[A](io: IO[A]) {
    def debug: IO[A] = io.map { a =>
      println(s"[${Thread.currentThread().getName}] $a")
      a
    }
  }

  val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:myimdb",
    "postgres",
    "postgres"
  )

  def findAllActorNames: IO[List[String]] = {
    val query = sql"select name from actors".query[String]
    val action = query.to[List]
    action.transact(xa)
  }

  def findActorById(id: Int): IO[Option[Actor]] = {
    val query = sql"select id, name from actors where id=$id".query[Actor]
    val action = query.option
    action.transact(xa)
  }

  val actorNamesStream: fs2.Stream[doobie.ConnectionIO, String] =
    sql"select names from actors".query[String].stream/*.compile.toList.transact(xa)*/

  // HC, HPS
  def findActorByName(name: String): IO[Option[Actor]] = {
    val queryString = "select id, name from actors where name = ?"
    HC.stream[Actor](
      queryString,
      HPS.set(name),
      100
    ).compile.toList.map(_.headOption).transact(xa)
  }

  // fragments
  def findActorsByInitialLetter(letter: String): IO[List[Actor]] = {
    val selectPart = fr"select id, name"
    val fromPart = fr"from actors"
    val wherePart = fr"where LEFT(name, 1) = $letter"
    val statement = selectPart ++ fromPart ++ wherePart

    statement.query[Actor].stream.compile.toList.transact(xa)
  }

  // updates
  def saveActor(id: Int, name: String): IO[Int] = {
    val query = sql"insert into actors (id, name) values ($id, $name)"
    query.update.run.transact(xa)
  }

  def saveActorV2(id: Int, name: String): IO[Int] = {
    val queryString = "insert into actors (id, name) values (?, ?)"
    Update[Actor](queryString).run(Actor(id, name))
      .transact(xa)
  }

  // auto generated ids
  def saveActorAutoGenerated(name: String): IO[Int] = {
    sql"insert into actors (name) values ($name);"
      .update.withUniqueGeneratedKeys[Int]("id")
      .transact(xa)
  }

  // update / insert many
  def saveMultipleActors(actorNames: List[String]): IO[List[Actor]] = {
    val insertStatement = "insert into actors (name) values (?)"
    val updateAction = Update[String](insertStatement).updateManyWithGeneratedKeys[Actor]("id", "name")(actorNames)
    updateAction.compile.toList.transact(xa)
  }

  // type classes
  class ActorName(val value: String) {
    override def toString: String = value
  }
  object ActorName {
    implicit val actorNameGet: Get[ActorName] = Get[String].map(string => new ActorName(string))
    implicit val actorNamePut: Put[ActorName] = Put[String].contramap(actorName => actorName.value)
  }

  def findAllActorNamesCustomClass: IO[List[ActorName]] =
    sql"select name from actors".query[ActorName].to[List].transact(xa)

  // "value types"
  case class DirectorId(id: Int)
  case class DirectorName(name: String)
  case class DirectorLastName(lastName: String)
  case class Director(id: DirectorId, name: DirectorName, lastName: DirectorLastName)
  object Director {
    implicit val directorRead: Read[Director] = Read[(Int, String, String)].map {
      case (id, name, lastName) => Director(DirectorId(id), DirectorName(name), DirectorLastName(lastName))
    }

    implicit val directorWrite: Write[Director] = Write[(Int, String, String)].contramap {
      case Director(DirectorId(id), DirectorName(name), DirectorLastName(lastName)) => (id, name, lastName)
    }
  }

  import doobie.postgres._
  import doobie.postgres.implicits._


  // write large queries
  def findMovieByTitle(title: String): IO[Option[Movie]] = {
    val statement =
      sql"""
           select m.id, m.title, m.year_of_production, array_agg(a.name) as actors, d.name || ' ' || d.last_name
                from movies m
                join movies_actors ma on m.id = ma.movie_id
                join actors a on ma.actor_id = a.id
                join directors d on m.director_id = d.id
                where m.title = $title
                group by (m.id, m.title, m.year_of_production, d.name, d.last_name)
           """
    statement.query[Movie].option.transact(xa)
  }

  def findMovieByTitleV2(title: String): IO[Option[Movie]] = {
    def findMovieByTitle() =
      sql"select id, title, year_of_production, director_id from movies where title = $title"
        .query[(UUID, String, Int, Int)].option

    def findDirectorById(directorId: Int) =
      sql"select name, last_name from directors where id = $directorId"
        .query[(String, String)].option

    def findActorsByMoviesId(movieId: UUID) =
      sql"""
           select a.name
           from actors a
           join movies_actors ma on a.id = ma.actor_id
           where ma.movie_id = $movieId
           """
        .query[String]
        .to[List]

    val query = for {
      maybeMovie <- findMovieByTitle()
      maybeDirector <- maybeMovie match {
        case Some((_, _, _, directorId)) => findDirectorById(directorId)
        case None => Option.empty[(String, String)].pure[ConnectionIO]
      }
      actors <- maybeMovie match {
        case Some((movieId, _, _, _)) => findActorsByMoviesId(movieId)
        case None => List.empty[String].pure[ConnectionIO]
      }
    } yield for {
      (id, title, year, _) <- maybeMovie
      (firstName, lastName) <- maybeDirector
    } yield Movie(id.toString, title, year, actors, s"$firstName $lastName")
    query.transact(xa)
  }


  override def run(args: List[String]): IO[ExitCode] = {
    // IO(println("Hello, doobie")).as(ExitCode.Success)
//    findAllActorNames.debug.as(ExitCode.Success)
//    findActorById(1).debug.as(ExitCode.Success)
//    findAllActorNames.debug.as(ExitCode.Success)
//    findActorByName("Henry Cavill").debug.as(ExitCode.Success)
//    findActorsByInitialLetter("H").debug.as(ExitCode.Success)
//    saveActor(99, "Mohsen").debug.as(ExitCode.Success)
//    saveActorAutoGenerated("Jim").debug.as(ExitCode.Success)
//    saveActorV2(8, "Mary").debug.as(ExitCode.Success)
//    saveMultipleActors(List("Alice", "Bob", "Charlie")).debug.as(ExitCode.Success)
//    findAllActorNamesCustomClass.debug.as(ExitCode.Success)
//    findMovieByTitle("Zack Snyder's Justice League").debug.as(ExitCode.Success)
    findMovieByTitleV2("Zack Snyder's Justice League").debug.as(ExitCode.Success)
  }

}
