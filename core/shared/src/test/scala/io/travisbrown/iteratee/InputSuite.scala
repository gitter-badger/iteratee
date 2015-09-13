package io.travisbrown.iteratee

import algebra.{ Eq, Semigroup }
import algebra.laws.GroupLaws
import cats.std.{ IntInstances, OptionInstances }
import cats.laws.discipline.{ MonadTests, SemigroupKTests, TraverseTests }
import org.scalatest.FunSuite
import org.typelevel.discipline.scalatest.Discipline

class InputSuite extends FunSuite with Discipline with IntInstances with OptionInstances
  with ArbitraryKInstances {
  checkAll("Input[Int]", GroupLaws[Input[Int]].semigroup)
  checkAll("Input[Int]", MonadTests[Input].monad[Int, Int, Int])
  checkAll("Input[Int]", TraverseTests[Input].traverse[Int, Int, Int, Int, Option, Option])
}
