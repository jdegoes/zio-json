package testzio.json

import scala.collection.immutable

import zio.Chunk
import zio.json._
import zio.json.ast._
import io.circe
import TestUtils._
import scalaprops._
import Property.{ implies, prop, property }
import org.typelevel.jawn.{ ast => jawn }
import scala.collection.mutable

import utest._
import testzio.json.data.googlemaps._
import testzio.json.data.twitter._

// testOnly *DecoderTest
object DecoderTest extends TestSuite {

  object exampleproducts {
    case class Parameterless()
    object Parameterless {
      implicit val decoder: JsonDecoder[Parameterless] =
        DeriveJsonDecoder.gen[Parameterless]
    }

    @no_extra_fields
    case class OnlyString(s: String)
    object OnlyString {
      implicit val decoder: JsonDecoder[OnlyString] =
        DeriveJsonDecoder.gen[OnlyString]
    }
  }

  object examplesum {

    sealed abstract class Parent
    object Parent {
      implicit val decoder: JsonDecoder[Parent] = DeriveJsonDecoder.gen[Parent]
    }
    case class Child1() extends Parent
    case class Child2() extends Parent
  }

  object examplealtsum {

    @discriminator("hint")
    sealed abstract class Parent
    object Parent {
      implicit val decoder: JsonDecoder[Parent] = DeriveJsonDecoder.gen[Parent]
    }
    @hint("Cain")
    case class Child1() extends Parent
    @hint("Abel")
    case class Child2() extends Parent
  }

  val tests = Tests {
    test("primitives") {
      // this big integer consumes more than 128 bits
      "170141183460469231731687303715884105728".fromJson[java.math.BigInteger] ==> Left(
        "(expected a 128 bit BigInteger)"
      )
    }

    test("eithers") {
      val bernies = List("""{"a":1}""", """{"left":1}""", """{"Left":1}""")
      val trumps  = List("""{"b":2}""", """{"right":2}""", """{"Right":2}""")

      bernies.foreach(s => s.fromJson[Either[Int, Int]] ==> Right(Left(1)))

      trumps.foreach(s => s.fromJson[Either[Int, Int]] ==> Right(Right(2)))

    }

    test("parameterless products") {
      import exampleproducts._
      """{}""".fromJson[Parameterless] ==> Right(Parameterless())

      // actually anything works... consider this a canary test because if only
      // the empty object is supported that's fine.
      """null""".fromJson[Parameterless] ==> Right(Parameterless())
      """{"field":"value"}""".fromJson[Parameterless] ==> Right(
        Parameterless()
      )
    }

    test("no extra fields") {
      import exampleproducts._

      """{"s":""}""".fromJson[OnlyString] ==> Right(OnlyString(""))

      """{"s":"","t":""}""".fromJson[OnlyString] ==> Left(
        "(invalid extra field)"
      )
    }

    test("sum encoding") {
      import examplesum._
      """{"Child1":{}}""".fromJson[Parent] ==> Right(Child1())
      """{"Child2":{}}""".fromJson[Parent] ==> Right(Child2())
      """{"type":"Child1"}""".fromJson[Parent] ==> Left(
        "(invalid disambiguator)"
      )
    }

    test("sum alternative encoding") {
      import examplealtsum._

      """{"hint":"Cain"}""".fromJson[Parent] ==> Right(Child1())
      """{"hint":"Abel"}""".fromJson[Parent] ==> Right(Child2())
      """{"hint":"Samson"}""".fromJson[Parent] ==> Left(
        "(invalid disambiguator)"
      )
      """{"Cain":{}}""".fromJson[Parent] ==> Left(
        "(missing hint 'hint')"
      )
    }

    test("googleMapsNormal") {
      val jsonString = getResourceAsString("google_maps_api_response.json")
      jsonString.fromJson[DistanceMatrix] ==>
        circe.parser.decode[DistanceMatrix](jsonString)
    }

    test("googleMapsCompact") {
      val jsonStringCompact =
        getResourceAsString("google_maps_api_compact_response.json")
      jsonStringCompact.fromJson[DistanceMatrix] ==>
        circe.parser.decode[DistanceMatrix](jsonStringCompact)
    }

    test("googleMapsExtra") {
      val jsonStringExtra = getResourceAsString("google_maps_api_extra.json")
      jsonStringExtra.fromJson[DistanceMatrix] ==>
        circe.parser.decode[DistanceMatrix](jsonStringExtra)
    }

    test("googleMapsError") {
      val jsonStringErr =
        getResourceAsString("google_maps_api_error_response.json")
      jsonStringErr.fromJson[DistanceMatrix] ==
        Left(".rows[0].elements[0].distance.value(missing)")
    }

    test("googleMapsAst") {
      getResourceAsString("google_maps_api_response.json").fromJson[Json] ==>
        getResourceAsString("google_maps_api_compact_response.json").fromJson[Json]
    }

    test("twitter") {
      val input    = getResourceAsString("twitter_api_response.json")
      val expected = circe.parser.decode[List[Tweet]](input)
      val got      = input.fromJson[List[Tweet]]
      got ==> expected
    }

    test("geojson1") {
      import testzio.json.data.geojson.generated._
      val input    = getResourceAsString("che.geo.json")
      val expected = circe.parser.decode[GeoJSON](input)
      val got      = input.fromJson[GeoJSON]
      got ==> expected
    }

    test("geojson1 alt") {
      import testzio.json.data.geojson.handrolled._
      val input    = getResourceAsString("che.geo.json")
      val expected = circe.parser.decode[GeoJSON](input)
      val got      = input.fromJson[GeoJSON]
      got ==> expected
    }

    test("geojson2") {
      import testzio.json.data.geojson.generated._
      val input    = getResourceAsString("che-2.geo.json")
      val expected = circe.parser.decode[GeoJSON](input)
      val got      = input.fromJson[GeoJSON]
      got ==> expected
    }

    test("geojson2 lowlevel") {
      import testzio.json.data.geojson.generated._
      // this uses a lower level Reader to ensure that the more general recorder
      // impl is covered by the tests
      val expected =
        circe.parser.decode[GeoJSON](getResourceAsString("che-2.geo.json"))
      val input = getResourceAsReader("che-2.geo.json")
      val got   = JsonDecoder[GeoJSON].unsafeDecode(Chunk.empty, input)
      input.close()
      Right(got) ==> expected
    }

    test("unicode") {
      """"€🐵🥰"""".fromJson[String] ==> Right("€🐵🥰")
    }

    // collections tests contributed by Piotr Paradziński
    test("Seq") {
      val jsonStr  = """["5XL","2XL","XL"]"""
      val expected = Seq("5XL", "2XL", "XL")
      jsonStr.fromJson[Seq[String]] ==> Right(expected)
    }

    test("Vector") {
      val jsonStr  = """["5XL","2XL","XL"]"""
      val expected = Vector("5XL", "2XL", "XL")
      jsonStr.fromJson[Vector[String]] ==> Right(expected)
    }

    test("SortedSet") {
      val jsonStr  = """["5XL","2XL","XL"]"""
      val expected = immutable.SortedSet("5XL", "2XL", "XL")
      jsonStr.fromJson[immutable.SortedSet[String]] ==> Right(expected)
    }

    test("HashSet") {
      val jsonStr  = """["5XL","2XL","XL"]"""
      val expected = immutable.HashSet("5XL", "2XL", "XL")
      jsonStr.fromJson[immutable.HashSet[String]] ==> Right(expected)
    }

    test("Set") {
      val jsonStr  = """["5XL","2XL","XL"]"""
      val expected = Set("5XL", "2XL", "XL")
      jsonStr.fromJson[Set[String]] ==> Right(expected)
    }

    test("Map") {
      val jsonStr  = """{"5XL":3,"2XL":14,"XL":159}"""
      val expected = Map("5XL" -> 3, "2XL" -> 14, "XL" -> 159)
      jsonStr.fromJson[Map[String, Int]] ==> Right(expected)
    }

    test("jawn test data: bar") {
      testAst("bar")
    }

    test("jawn test data: bla25") {
      testAst("bla25")
    }

    test("jawn test data: bla2") {
      testAst("bla2")
    }

    test("jawn test data: countries.geo") {
      testAst("countries.geo")
    }

    test("jawn test data: dkw-sample") {
      testAst("dkw-sample")
    }

    test("jawn test data: foo") {
      testAst("foo")
    }

    test("jawn test data: qux1") {
      testAst("qux1")
    }

    test("jawn test data: qux2") {
      testAst("qux2")
    }

    test("jawn test data: ugh10k") {
      testAst("ugh10k")
    }

    // TODO it would be good to test with https://github.com/nst/JSONTestSuite
  }

  def testAst(name: String) = {
    val input     = getResourceAsString(s"jawn/${name}.json")
    val expected  = jawn.JParser.parseFromString(input).toEither.map(fromJawn)
    val got       = input.fromJson[Json].map(normalize)
    val gotf      = s"${name}-got.json"
    val expectedf = s"${name}-expected.json"

    def e2s[A, B](e: Either[A, B]) =
      e match {
        case Left(left)   => left.toString
        case Right(right) => right.toString
      }
    if (expected != got) {
      writeFile(gotf, e2s(got))
      writeFile(expectedf, e2s(expected))
    }
    scala.Predef.assert(
      got == expected,
      s"dumped .json files, use `cmp <(jq . ${expectedf}) <(jq . ${gotf})`"
    ) // errors are too big
  }

  // reorder objects to match jawn's lossy AST (and dedupe)
  def normalize(ast: Json): Json =
    ast match {
      case Json.Obj(values) =>
        Json.Obj(
          values
            .distinctBy(_._1)
            .map { case (k, v) => (k, normalize(v)) }
            .sortBy(_._1)
        )
      case Json.Arr(values) => Json.Arr(values.map(normalize(_)))
      case other            => other
    }

  def fromJawn(ast: jawn.JValue): Json =
    ast match {
      case jawn.JNull      => Json.Null
      case jawn.JTrue      => Json.Bool(true)
      case jawn.JFalse     => Json.Bool(false)
      case jawn.JString(s) => Json.Str(s)
      case jawn.LongNum(i) =>
        Json.Num(new java.math.BigDecimal(java.math.BigInteger.valueOf(i)))
      case jawn.DoubleNum(d) => Json.Num(new java.math.BigDecimal(d))
      case jawn.DeferLong(i) =>
        Json.Num(new java.math.BigDecimal(new java.math.BigInteger(i)))
      case jawn.DeferNum(n) => Json.Num(new java.math.BigDecimal(n))
      case jawn.JArray(vs)  => Json.Arr(Chunk.fromArray(vs).map(fromJawn))
      case jawn.JObject(es) =>
        Json.Obj(Chunk.fromIterable(es).sortBy(_._1).map { case (k, v) => (k, fromJawn(v)) })
    }

}
