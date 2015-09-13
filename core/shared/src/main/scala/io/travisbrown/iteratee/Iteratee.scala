package io.travisbrown.iteratee

import algebra.Monoid
import cats.{ Applicative, Comonad, FlatMap, Functor, Id, Monad, MonoidK, Show }
import cats.arrow.NaturalTransformation
import cats.functor.Contravariant

abstract class IterateeFolder[E, F[_], A, B] extends StepFolder[E, F, A, F[B]] {
  def onCont(k: Input[E] => Iteratee[E, F, A]): F[B]
  def onDone(value: A, remainder: Input[E]): F[B]
}

/**
 * A data sink.
 *
 * Represents a value of type `F[Step[E, F, A]]`
 *
 * @see [[Step]]
 *
 * @tparam E The type of the input data (mnemonic: '''E'''lement type)
 * @tparam F The type constructor representing an effect.
 *           The type constructor [[cats.Id]] is used to model pure computations, and is fixed as such in the type alias [[PureIteratee]].
 * @tparam A The type of the calculated result
 */
sealed class Iteratee[E, F[_], A](val value: F[Step[E, F, A]]) {
  def foldWith[Z](folder: StepFolder[E, F, A, Z])(implicit F: Functor[F]): F[Z] =
    F.map(value)(_.foldWith(folder))

  def foldWithM[Z](folder: StepFolder[E, F, A, F[Z]])(implicit F: FlatMap[F]): F[Z] =
    F.flatMap(value)(_.foldWith(folder))

  /**
   * Run this iteratee
   */
  def run(implicit F: Monad[F]): F[A] = F.map(feedE(Enumerator.enumEof[E, F]).value)(_.unsafeValue)

  def flatMap[B](f: A => Iteratee[E, F, B])(implicit F: Monad[F]): Iteratee[E, F, B] = {
    def through(x: Iteratee[E, F, A]): Iteratee[E, F, B] =
      Iteratee.iteratee(
        F.flatMap(x.value)((s: Step[E, F, A]) => s.foldWith[F[Step[E, F, B]]](
          new StepFolder[E, F, A, F[Step[E, F, B]]] {
            def onCont(f: Input[E] => Iteratee[E, F, A]): F[Step[E, F, B]] = F.pure(Step.cont(u => through(f(u))))
            def onDone(value: A, remainder: Input[E]): F[Step[E, F, B]] = if (remainder.isEmpty)
              f(value).value
            else
              F.flatMap(f(value).value)(
                _.foldWith(
                  new StepFolder[E, F, B, F[Step[E, F, B]]] {
                    def onCont(ff: Input[E] => Iteratee[E, F, B]): F[Step[E, F, B]] = ff(remainder).value
                    def onDone(aa: B, r: Input[E]): F[Step[E, F, B]] = F.pure(Step.done[E, F, B](aa, remainder))
                  }
                )
              )
          }
        )))
    through(this)
  }

  def map[B](f: A => B)(implicit F: Monad[F]): Iteratee[E, F, B] =
    flatMap(a => Step.done[E, F, B](f(a), Input.empty).pointI)

  def contramap[EE](f: EE => E)(implicit F: Monad[F]): Iteratee[EE, F, A] = {
    def step(s: Step[E, F, A]): Iteratee[EE, F, A] = s.foldWith(
      new StepFolder[E, F, A, Iteratee[EE, F, A]] {
        def onCont(k: Input[E] => Iteratee[E, F, A]): Iteratee[EE, F, A] =
          Iteratee.cont((in: Input[EE]) => k(in.map(i => f(i))).feed(step))
        def onDone(value: A, remainder: Input[E]): Iteratee[EE, F, A] =
          Iteratee.done(value, if (remainder.isEof) Input.eof else Input.empty)
      }
    )

    this.feed(step)
  }

  /**
   * Combine this Iteratee with an Enumerator-like function.
   *
   * Often used in combination with the implicit views such as `enumStream` and `enumIterator`, for example:
   *
   * {{{
   * head[Unit, Int, Id] >>== Stream.continually(1) // enumStream(Stream.continually(1))
   * }}}
   *
   * @param f An Enumerator-like function. If the type parameters `EE` and `BB` are chosen to be
   *          `E` and `B` respectively, the type of `f` is equivalent to `Enumerator[E, F, A]`.
   */
  def feed[EE, AA](f: Step[E, F, A] => Iteratee[EE, F, AA])(implicit F: FlatMap[F]): Iteratee[EE, F, AA] =
    new Iteratee(F.flatMap(value)(step => f(step).value))

  def feedE(enum: Enumerator[E, F])(implicit F: FlatMap[F]): Iteratee[E, F, A] =
    new Iteratee(F.flatMap(value)(step => enum(step).value))

  def through[O](enumeratee: Enumeratee[O, E, F])(implicit F: Monad[F]): Iteratee[O, F, A] =
    Iteratee.joinI(new Iteratee(F.flatMap(value)(step => enumeratee(step).value)))

  def mapI[G[_]](f: NaturalTransformation[F, G])(implicit F: Functor[F]): Iteratee[E, G, A] = {
    def step: Step[E, F, A] => Step[E, G, A] =
      _.foldWith(
        new StepFolder[E, F, A, Step[E, G, A]] {
          def onCont(k: Input[E] => Iteratee[E, F, A]): Step[E, G, A] = Step.cont[E, G, A](k.andThen(loop))
          def onDone(value: A, remainder: Input[E]): Step[E, G, A] = Step.done[E, G, A](value, remainder)
        }
      )
    def loop: Iteratee[E, F, A] => Iteratee[E, G, A] = i => Iteratee.iteratee(f(F.map(i.value)(step)))
    loop(this)
  }

  def up[G[_]](implicit G: Applicative[G], F: Comonad[F]): Iteratee[E, G, A] =
    mapI(
      new NaturalTransformation[F, G] {
        def apply[A](a: F[A]): G[A] = G.pure(F.extract(a))
      }
    )

  /**
   * Feeds input elements to this iteratee until it is done, feeds the produced value to the 
   * inner iteratee. Then this iteratee will start over, looping until the inner iteratee is done.
   */
  def sequenceI(implicit m: Monad[F]): Enumeratee[E, A, F] = Enumeratee.sequenceI(this)

  def zip[B](other: Iteratee[E, F, B])(implicit F: Monad[F]): Iteratee[E, F, (A, B)] = {
    type Pair[Z] = (Option[(Z, Input[E])], Iteratee[E, F, Z])

    def step[Z](i: Iteratee[E, F, Z], in: Input[E]): Iteratee[E, F, Pair[Z]] =
      Iteratee.liftM(
        i.foldWith(
          new StepFolder[E, F, Z, Pair[Z]] {
            def onCont(k: Input[E] => Iteratee[E, F, Z]): Pair[Z] = (None, k(in))
            def onDone(value: Z, remainder: Input[E]): Pair[Z] =
              (Some((value, remainder)), Iteratee.done(value, remainder))
          }
        )
      )

    def loop(x: Iteratee[E, F, A], y: Iteratee[E, F, B])(in: Input[E]): Iteratee[E, F, (A, B)] =
      in.foldWith(
        new InputFolder[E, Iteratee[E, F, (A, B)]] {
          def onEmpty: Iteratee[E, F, (A, B)] = Iteratee.cont(loop(x, y))
          def onEl(e: E): Iteratee[E, F, (A, B)] = step(x, in).flatMap {
            case (a, xx) =>
              step(y, in).flatMap {
                case (b, yy) => (a, b) match {
                  case (Some((a, ae)), Some((b, be))) =>
                    Iteratee.done((a, b), if (ae.isEl || ae.isChunk) ae else be)
                  case _ => Iteratee.cont(loop(xx, yy))
                }
              }
          }

          def onChunk(es: Seq[E]): Iteratee[E, F, (A, B)] = step(x, in).flatMap {
            case (a, xx) =>
              step(y, in).flatMap {
                case (b, yy) => (a, b) match {
                  case (Some((a, ae)), Some((b, be))) =>
                    Iteratee.done((a, b), if (ae.isEl || ae.isChunk) ae else be)
                  case _ => Iteratee.cont(loop(xx, yy))
                }
              }
          }

          def onEof: Iteratee[E, F, (A, B)] =
            x.feedE(Enumerator.enumEof[E, F]).flatMap(a =>
              y.feedE(Enumerator.enumEof[E, F]).map((a, _))
            )
      }
    )
    Iteratee.cont(loop(this, other))
  }
}

sealed abstract class IterateeInstances0 {
  implicit def IterateeMonad[E, F[_]](implicit
    F0: Monad[F]
  ): Monad[({ type L[x] = Iteratee[E, F, x] })#L] =
    new IterateeMonad[E, F] {
      implicit val F = F0
    }

  implicit def PureIterateeMonad[E]: Monad[({ type L[x] = PureIteratee[E, x] })#L] =
    IterateeMonad[E, Id]
}

sealed abstract class IterateeInstances extends IterateeInstances0 {
  implicit def IterateeContravariant[
    F[_]: Monad,
    A
  ]: Contravariant[({ type L[x] = Iteratee[x, F, A] })#L] =
    new Contravariant[({ type L[x] = Iteratee[x, F, A] })#L] {
      def contramap[E, EE](r: Iteratee[E, F, A])(f: EE => E) = r.contramap(f)
    }
}

object Iteratee extends IterateeInstances {
  /**
   * Lift an effectful value into an iteratee.
   */
  def liftM[E, F[_]: Monad, A](fa: F[A]): Iteratee[E, F, A] =
    iteratee(Monad[F].map(fa)(a => Step.done(a, Input.empty)))

  def iteratee[E, F[_], A](s: F[Step[E, F, A]]): Iteratee[E, F, A] = new Iteratee[E, F, A](s)

  def cont[E, F[_]: Applicative, A](c: Input[E] => Iteratee[E, F, A]): Iteratee[E, F, A] =
    Step.cont(c).pointI

  def done[E, F[_]: Applicative, A](d: A, r: Input[E]): Iteratee[E, F, A] =
    Step.done(d, r).pointI

  def joinI[E, F[_]: Monad, I, B](it: Iteratee[E, F, Step[I, F, B]]): Iteratee[E, F, B] = {
    val M0 = Iteratee.IterateeMonad[E, F]

    def check: Step[I, F, B] => Iteratee[E, F, B] = _.foldWith(
      new StepFolder[I, F, B, Iteratee[E, F, B]] {
        def onCont(k: Input[I] => Iteratee[I, F, B]): Iteratee[E, F, B] = k(Input.eof).feed(
          s => if (s.isDone) check(s) else sys.error("diverging iteratee")
        )
        def onDone(value: B, remainder: Input[I]): Iteratee[E, F, B] = M0.pure(value)
      }
    )

    it.flatMap(check)
  }

  /**
   * An iteratee that consumes all of the input into something that is MonoidK and Applicative.
   */
  def consumeIn[E, F[_]: Monad, A[_]: MonoidK: Applicative]: Iteratee[E, F, A[E]] = {
    def step(s: Input[E]): Iteratee[E, F, A[E]] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, A[E]]] {
        def onEmpty: Iteratee[E, F, A[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, A[E]] = cont(step).map(a => MonoidK[A].combine[E](Applicative[A].pure(e), a))
        def onChunk(es: Seq[E]): Iteratee[E, F, A[E]] = cont(step).map(a => es.foldRight(a)((e, acc) => (MonoidK[A].combine[E](Applicative[A].pure(e), acc))))
        def onEof: Iteratee[E, F, A[E]] = done(MonoidK[A].empty[E], Input.eof[E])
      }      
    )
    cont(step)
  }

  def consume[E, F[_]: Monad]: Iteratee[E, F, Vector[E]] = {
    def step(s: Input[E]): Iteratee[E, F, Vector[E]] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, Vector[E]]] {
        def onEmpty: Iteratee[E, F, Vector[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, Vector[E]] = cont(step).map(e +: _)
        def onChunk(es: Seq[E]): Iteratee[E, F, Vector[E]] = cont(step).map(es ++: _)
        def onEof: Iteratee[E, F, Vector[E]] = done(Vector.empty[E], Input.eof[E])
      }      
    )
    cont(step)
  }

  def collectT[E, F[_], A[_]](implicit M: Monad[F], mae: Monoid[A[E]], pointed: Applicative[A]): Iteratee[E, F, A[E]] = {
    import cats.syntax.semigroup._
    def step(s: Input[E]): Iteratee[E, F, A[E]] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, A[E]]] {
        def onEmpty: Iteratee[E, F, A[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, A[E]] = cont(step).map(a => Applicative[A].pure(e) |+| a)
        def onChunk(es: Seq[E]): Iteratee[E, F, A[E]] = cont(step).map(a => es.foldRight(a)((e, acc) => Applicative[A].pure(e) |+| acc))
        def onEof: Iteratee[E, F, A[E]] = done(Monoid[A[E]].empty, Input.eof[E])
      }
    )
    cont(step)
  }

  /**An iteratee that consumes the head of the input **/
  def head[E, F[_] : Applicative]: Iteratee[E, F, Option[E]] = {
    def step(s: Input[E]): Iteratee[E, F, Option[E]] = s.normalize.foldWith(
      new InputFolder[E, Iteratee[E, F, Option[E]]] {
        def onEmpty: Iteratee[E, F, Option[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, Option[E]] = done(Some(e), Input.empty[E])
        def onChunk(es: Seq[E]): Iteratee[E, F, Option[E]] = done(Some(es.head), Input.chunk(es.tail))
        def onEof: Iteratee[E, F, Option[E]] = done(None, Input.eof[E])
      }
    )
    cont(step)
  }

  def headDoneOr[E, F[_] : Monad, B](b: => B, f: E => Iteratee[E, F, B]): Iteratee[E, F, B] = {
    head[E, F] flatMap {
      case None => done(b, Input.eof)
      case Some(a) => f(a)
    }
  }

  /**An iteratee that returns the first element of the input **/
  def peek[E, F[_]: Applicative]: Iteratee[E, F, Option[E]] = {
    def step(s: Input[E]): Iteratee[E, F, Option[E]] = s.normalize.foldWith(
      new InputFolder[E, Iteratee[E, F, Option[E]]] {
        def onEmpty: Iteratee[E, F, Option[E]] = cont(step)
        def onEl(e: E): Iteratee[E, F, Option[E]] = done(Some(e), s)
        def onChunk(es: Seq[E]): Iteratee[E, F, Option[E]] = done(Some(es.head), s)
        def onEof: Iteratee[E, F, Option[E]] = done(None, Input.eof[E])
      }
    )
    cont(step)
  }

  def peekDoneOr[E, F[_]: Monad, B](b: => B, f: E => Iteratee[E, F, B]): Iteratee[E, F, B] =
    peek[E, F].flatMap {
      case None => done(b, Input.eof)
      case Some(a) => f(a)
    }

  /**
   * Iteratee that collects all inputs in reverse with the given reducer.
   *
   * This iteratee is useful for F[_] with efficient cons, i.e. List.
   */
  def reversed[A, F[_]: Applicative]: Iteratee[A, F, List[A]] =
    Iteratee.fold[A, F, List[A]](Nil)((acc, e) => e :: acc)

  /**
   * Iteratee that collects the first n inputs.
   */
  def take[A, F[_]: Applicative](n: Int): Iteratee[A, F, Vector[A]] = {
    def loop(acc: Vector[A], n: Int)(in: Input[A]): Iteratee[A, F, Vector[A]] = in.foldWith(
      new InputFolder[A, Iteratee[A, F, Vector[A]]] {
        def onEmpty: Iteratee[A, F, Vector[A]] = cont(loop(acc, n))
        def onEl(e: A): Iteratee[A, F, Vector[A]] = if (n < 1) done(acc, in) else cont(loop(acc :+ e, n - 1))
        def onChunk(es: Seq[A]): Iteratee[A, F, Vector[A]] = {
          val c = es.lengthCompare(n)

          if (c < 0) cont(loop(acc ++ es, n - es.size)) else
          if (c == 0) done(acc ++ es, Input.empty) else {
            val (taken, left) = es.splitAt(n)

            done(acc ++ taken, Input.chunk(left))
          }
        }
        def onEof: Iteratee[A, F, Vector[A]] = done(acc, in)
      }
    )
    cont(loop(Vector.empty[A], n))
  }

  /**
   * Iteratee that collects inputs until the input element fails a test.
   */
  def takeWhile[A, F[_]: Applicative](p: A => Boolean): Iteratee[A, F, Vector[A]] = {
    def loop(acc: Vector[A])(s: Input[A]): Iteratee[A, F, Vector[A]] = s.foldWith(
      new InputFolder[A, Iteratee[A, F, Vector[A]]] {
        def onEmpty: Iteratee[A, F, Vector[A]] = cont(loop(acc))
        def onEl(e: A): Iteratee[A, F, Vector[A]] =
          if (p(e)) cont(loop(acc :+ e)) else done(acc, s)

        def onChunk(es: Seq[A]): Iteratee[A, F, Vector[A]] = {
          val (before, after) = es.span(p)

          if (after.isEmpty) cont(loop(acc ++ before)) else done(acc ++ before, Input.chunk(after))
        }
        def onEof: Iteratee[A, F, Vector[A]] = done(acc, Input.eof)
      }
    )
    cont(loop(Vector.empty))
  }

  /**An iteratee that skips the first n elements of the input **/
  def drop[E, F[_]: Applicative](n: Int): Iteratee[E, F, Unit] = {
    def step(s: Input[E]): Iteratee[E, F, Unit] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, Unit]] {
        def onEmpty: Iteratee[E, F, Unit] = cont(step)
        def onEl(e: E): Iteratee[E, F, Unit] = drop(n - 1)
        def onChunk(es: Seq[E]): Iteratee[E, F, Unit] = {
          val len = es.size

          if (len <= n) drop(n - len) else done((), Input.chunk(es.drop(n)))
        }
        def onEof: Iteratee[E, F, Unit] = done((), Input.eof[E])
      }
    )

    if (n <= 0) done((), Input.empty[E]) else cont(step)
  }

  /**
   * An iteratee that skips elements while the predicate evaluates to true.
   */
  def dropWhile[E, F[_] : Applicative](p: E => Boolean): Iteratee[E, F, Unit] = {
    def step(s: Input[E]): Iteratee[E, F, Unit] = s.foldWith(
      new InputFolder[E, Iteratee[E, F, Unit]] {
        def onEmpty: Iteratee[E, F, Unit] = cont(step)
        def onEl(e: E): Iteratee[E, F, Unit] = if (p(e)) dropWhile(p) else done((), s)
        def onChunk(es: Seq[E]): Iteratee[E, F, Unit] = {
          val after = es.dropWhile(p)

          if (after.isEmpty) dropWhile(p) else done((), Input.chunk(after))
        }
        def onEof: Iteratee[E, F, Unit] = done((), Input.eof[E])
      }
    )
    cont(step)
  }

  /**
   * Produces chunked output split by the given predicate. Currently broken.
   */
  def groupBy[A, F[_]: Monad](p: (A, A) => Boolean): Iteratee[A, F, Vector[A]] =
    Iteratee.peek[A, F].flatMap[Vector[A]] {
      case None => Iteratee.done(Vector.empty, Input.empty)
      case Some(h) => takeWhile[A, F](p(_, h))
    }

  def fold[E, F[_]: Applicative, A](init: A)(f: (A, E) => A): Iteratee[E, F, A] = {
    def step(acc: A): Input[E] => Iteratee[E, F, A] = _.foldWith(
      new InputFolder[E, Iteratee[E, F, A]] {
        def onEmpty: Iteratee[E, F, A] = cont(step(acc))
        def onEl(e: E): Iteratee[E, F, A] = cont(step(f(acc, e)))
        def onChunk(es: Seq[E]): Iteratee[E, F, A] = cont(step(es.foldLeft(init)(f)))
        def onEof: Iteratee[E, F, A] = done(acc, Input.eof[E])
      }
    )
    cont(step(init))
  }

  def foldM[E, F[_], A](init: A)(f: (A, E) => F[A])(implicit m: Monad[F]): Iteratee[E, F, A] = {
    def step(acc: A): Input[E] => Iteratee[E, F, A] = _.foldWith(
      new InputFolder[E, Iteratee[E, F, A]] {
        def onEmpty: Iteratee[E, F, A] = cont(step(acc))
        def onEl(e: E): Iteratee[E, F, A] = Iteratee.liftM(f(acc, e)).flatMap(a => cont(step(a)))
        def onChunk(es: Seq[E]): Iteratee[E, F, A] =
          Iteratee.liftM(
            es.foldLeft(m.pure(acc))((fa, e) => m.flatMap(fa)(a => f(a, e)))
          ).flatMap(a => cont(step(a)))
        def onEof: Iteratee[E, F, A] = done(acc, Input.eof[E])
      }
    )
    cont(step(init))
  }

  /**
   * An iteratee that counts and consumes the elements of the input
   */
  def length[E, F[_]: Applicative]: Iteratee[E, F, Int] = fold(0)((a, _) => a + 1)

  /**
   * An iteratee that checks if the input is EOF.
   */
  def isEof[E, F[_]: Applicative]: Iteratee[E, F, Boolean] = cont(in => done(in.isEof, in))

  def sum[E: Monoid, F[_]: Monad]: Iteratee[E, F, E] =
    foldM[E, F, E](Monoid[E].empty)((a, e) => Applicative[F].pure(Monoid[E].combine(a, e)))
}

private trait IterateeMonad[E, F[_]] extends Monad[({ type L[x] = Iteratee[E, F, x] })#L] {
  implicit def F: Monad[F]

  def pure[A](a: A): Iteratee[E, F, A] = Step.done(a, Input.empty).pointI
  override def map[A, B](fa: Iteratee[E, F, A])(f: A => B): Iteratee[E, F, B] = fa map f
  def flatMap[A, B](fa: Iteratee[E, F, A])(f: A => Iteratee[E, F, B]): Iteratee[E, F, B] = fa flatMap f
}
