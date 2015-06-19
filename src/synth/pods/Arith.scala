package synth.pods

import syntax.AstSugar._
import semantics.Prelude
import semantics.TypedTerm
import semantics.TypeTranslation.TypingSugar._
import semantics.TypeTranslation.Declaration
import semantics.pattern.MacroMap


trait Pod {
  val decl = new Declaration()
  val macros = MacroMap.empty
}

object Pod {
  class TacticalError(msg: String) extends Exception(msg) {}
}


object NatPod extends Pod {
  import Prelude.{N,B,↓}
  
  val _0 = TyTV("0", N)
  val _1 = TyTV("1", N)
  val z =  TyTV("z", N -> B)
  val nz = TyTV("~z", N -> B)
  val s =  TyTV("s", N -> N)
  val p =  TyTV("p", N -> N) //(N ∩ nz) -> N)
  
  private val i = $TyTV("i", N)
  
  override val decl = new Declaration(_0, _1, z, nz, s, p) where List(
      ↓(_0) & ↓(_1) & (TypedTerm(s :@ _0, N) =:= _1),
      z <-> TypedTerm(i ↦ (i =:= _0), N -> B),
      nz <-> TypedTerm(i ↦ ~(z :@ i), N -> B),
      ∀:(N, i => (↓(s :@ i) -> ~(TypedTerm(s :@ i, N) =:= i) )),
      ∀:(N, i => (↓(s :@ i) -> (TypedTerm(p :@ (s :@ i), N) =:= i) ))
    )
}



class TotalOrderPod(domain: Term) extends Pod {
  
  import Prelude.B
  
  private val D = domain
  
  val < = $TyTV("<", D ->: D ->: B)
  
  val sym = List(<.root)
  
  override val decl = new Declaration(<) where List(
      ∀:(D, (i, j) => (< :@ i :@ j) -> ~(< :@ j :@ i)),                   // anti-symmetry
      ∀:(D, (i, j) => ~(< :@ i :@ j) ->: ~(< :@ j :@ i) ->: (i =:= j)),    // totality
      ∀:(D, (i, j, k) => ~(< :@ i :@ j) ->: ~(< :@ j :@ k) ->: ~(< :@ i :@ k))    // transitivity
    )      
}

object TotalOrderPod {
  def apply(domain: Term) = new TotalOrderPod(domain)
}