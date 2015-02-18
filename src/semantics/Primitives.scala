package semantics

import syntax.Identifier
import syntax.AstSugar
import TypeTranslation.{MicroCode,In,Out,Check}
import TypeTranslation.InOut
import TypeTranslation.{emit,simplify,canonical}



object TypePrimitives {
  
  import AstSugar._
  
  /**
   * Strips type of checks to get the unrefined type.
   */
  def rawtype(micro: List[MicroCode]) = micro filter { case In(_) | Out(_) => true case _ => false }
  
  def rawtype(scope: Scope, micro: List[MicroCode]): List[MicroCode] = micro flatMap {
    case In(t) => Some(if (t.isLeaf) In(t) else In(rawtype(scope, t)))
    case Out(t) => Some(if (t.isLeaf) Out(t) else Out(rawtype(scope, t)))
    case _ => None
  }
  
  def rawtype(scope: Scope, typ: Term): Term = 
    TypeTranslation.canonical(rawtype(scope, TypeTranslation.emit(scope, typ)))
    
  /**
   * Counts the arguments in a function type.
   */
  def arity(micro: List[MicroCode]) = micro count { case In(_) => true case _ => false}

  def arity(typ: Term): Int = 
    if (typ.root == "->") (typ.subtrees.length - 1) + arity(typ.subtrees.last)
    else 0
  
  /**
   * Computes an intersection type in MicroCode form.
   */
  def intersection(decls: List[List[MicroCode]]): List[MicroCode] = {
    def isCheck(mc: MicroCode) = mc match { case Check(_,_) => true case _ => false }
    def isLeaf(mc: MicroCode) = (mc match { case In(t) => Some(t) case Out(t) => Some(t) case _ => None }) exists (_.isLeaf)
    def dir(mc: MicroCode) = mc match { case In(_) => InOut.IN case Out(_) => InOut.OUT }
    if (decls forall (_.isEmpty)) List()
    else if (decls.exists (l => !l.isEmpty && isCheck(l.head)))
      (decls flatMap (_ takeWhile isCheck)) ++ intersection(decls map (_ dropWhile isCheck))
    else {
      val heads = decls map (_.head)
      if (heads forall (_==heads.head)) heads.head :: intersection(decls map (_ drop 1))
      else if (heads forall isLeaf) throw new Scope.TypingException(s"incompatible type instructions: $heads")
      else ???  /* high-order arguments? */
    }
  }
  
  
  def intersection(scope: Scope, types: List[Term]): Term = {
    import syntax.Piping._
    val emits = types map (emit(scope, _))
    intersection(emits) |> canonical |> (simplify(scope, _))
  }
  
  def curry(micro: List[MicroCode])(implicit scope: Scope): (List[MicroCode], List[MicroCode]) = {
    import syntax.Piping._
    def shift1[A](ab: (List[A], List[A])) = (ab._1 :+ ab._2.head, ab._2.tail)
    val (arg, tail) = (micro span { case In(_) => false case _ => true }) |> shift1
    val (checks, res) = tail span { case In(_) | Out(_) => false case _ => true }
    // make 'res' viable by removing any checks that depend on the first argument
    var depth = 0
    val viable = res filter {
      case In(_) | Out(_) => depth += 1 ; true
      case Check(_,arity) => arity <= depth
    }
    (curriedArg(arg ++ checks), viable)
  }
  
  private def curriedArg(micro: List[MicroCode])(implicit scope: Scope) = {
    micro match {
      case In(tpe) :: tail =>
        if (tpe.isLeaf) micro
        else if (!tail.isEmpty) throw new CurryingException(s"cannot curry ${micro}: high-order checks")
        else emit(scope, tpe)
      case _ =>
        throw new CurryingException(s"cannot curry ${micro}: expected In(...)")
    }
  }
 
  
  class CurryingException(msg: String) extends Scope.TypingException(msg)
}


object LambdaCalculus {
  
  import AstSugar._

  def beta(va: Identifier, body: Term, arg: Term): Term = {
    if (body.isLeaf && body.root == va) arg
    else T(body.root, body.subtrees map (x => beta(va, x, arg)))
  }
  
  def beta(fun: Term, arg: Term): Term = {
    assume(fun =~ ("↦", 2) && fun.subtrees(0).isLeaf)
    beta(fun.subtrees(0).root, fun.subtrees(1), arg)
  }
    
}
