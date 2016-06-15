

package codegen
import org.rogach.scallop.ScallopConf
import org.rogach.scallop.ScallopOption
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileReader
import ui.CLI
import com.mongodb.util.JSON
import com.mongodb.BasicDBObject
import report.data.DisplayContainer
import syntax.Formula
import syntax.AstSugar._
import syntax.Tree
import semantics.LambdaCalculus._
import semantics.TypedTerm.{preserve, typeOf_!}
import scala.collection.mutable.ListMap
import syntax.Identifier
import semantics.Prelude
import semantics.TypedTerm
import syntax.transform.Mnemonics
import semantics.Scope
import synth.pods.ConsPod
import report.console.NestedListTextFormat
import report.console.NestedListTextFormat
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.File
import semantics.TranslationError
import report.data.SerializationError


object Main {
  
  class CommandLineConfig(args: List[String]) extends ScallopConf(args toList) {
    val filenames = trailArg[List[String]](required=true) //opt[String]("files", required=true).map((_.split(",").toList))
    val outputfile = opt[String]("outfile", default=Some("output.cpp"))
  }
 
  def isAllDigits(x: String) = x forall Character.isDigit
  
  abstract class Expr

  abstract class Direction
  case object BWD extends Direction
  case object FWD extends Direction
  
  abstract class Lowup
  case object LOWER extends Lowup
  case object UPPER extends Lowup
  
  val fPreDefs = List("min","max","cons","+","ψ","θ","-","w","?","nil","δ","d","S","w'", "v")
  val memPreDefs = List("ψ","θ")
  val intervalDefs = List('I','J','K','L','M','N')
  val varList = List("i","j","k","p","q")
  
  //TODO: incorporate bazinga info!   
  case class Num(value: Int) extends Expr
  case class Interval(name: String) extends Expr
  case class GetBound(i: Interval, sel: Lowup) extends Expr
  case class Var(name: String, ii: Interval) extends Expr
  case class FunApp(f: String, args: List[Expr]) extends Expr
  case class Slash(isFunction: Boolean, slashes: List[Expr]) extends Expr
  case class Guarded(cond: Expr, v: Expr) extends Expr
  case class MemRead(arrayName: String, indices: List[Expr]) extends Expr
  case class VarB(name: String, lb:Expr, ub: Expr) extends Expr
  

  case class FunDef (name: String,args: List[Interval],body:Block)
  
  abstract class Stmt {
    def toPrettyTree : Tree[String] = this match {
      case DefIntervalSplit(i, superset, whichPart) =>
        new Tree(s"${i} = ${whichPart}(${superset});")
      case DefIntervalUnion(i, (lower, upper)) =>
        new Tree(s"${i} = UNION(${lower}, ${upper});")
      case DefVar(v, typ) => 
        new Tree(s"${typ} ${v};")
      case Assign(v,e) => new Tree(s"$v = $e;")
      case MemWrite(arrayName,indices,rhs) => 
        new Tree(s"$arrayName(${indices.mkString(",")}) = ${rhs};")
      case FunctionCall(name,params) =>
        new Tree(s"$name(${params.mkString(",")});")
      case For(v,lb,ub,dir,stmt) =>
        new Tree(s"FOR(${v},${ub},${lb},${dir})", List(stmt.toPrettyTree))
      case If(cond,caseThen,caseElse) =>
        new Tree(s"if(${cond})", List(caseThen.toPrettyTree, caseElse.toPrettyTree))
      case Fork(stmts) => //TODO: not here, but get parallel number of each stmt and re-organize AST
        new Tree("{fork}", stmts map (_.toPrettyTree) )
      case Block(stmts) =>
        new Tree("{}", stmts map (_.toPrettyTree) )
      case Parallel(i, stmt) =>
        new Tree(s"$i:", List(stmt.toPrettyTree))
    }
  }
  case class DefIntervalSplit(I: Interval, superset: Interval, whichPart: Lowup) extends Stmt
  case class DefIntervalUnion(I: Interval, subparts: (Interval, Interval)) extends Stmt
  case class DefVar(v: String, typ: String) extends Stmt
  case class Assign(v: String, e:Expr) extends Stmt
  case class MemWrite(arrayName: String,indices: List[Expr],rhs: Expr) extends Stmt
  case class FunctionCall(name: String, params: List[Expr]) extends Stmt
  case class For(v:String,lb:Expr,ub:Expr,dir:Direction,stmts:Stmt) extends Stmt
  case class If(cond:Expr,caseThen:Stmt,caseElse:Stmt) extends Stmt
  case class Fork(stmts:List[Stmt]) extends Stmt//can run these in parallel!
  case class Block(stmts:List[Stmt]) extends Stmt
  case class Parallel(i:Int, s:Stmt) extends Stmt
  
  implicit val cc = new DisplayContainer
  
  def isSlash(t: Term): Option[(List[Term])] = 
    if (t =~ ("/", 2)) { 
      isSlash(t.subtrees(0)) match {
        case Some(slashes) => Some(slashes :+ t.subtrees(1))
        case _ => Some(t.subtrees)
      }
    }
    else if (t =~ (":", 2)) isSlash(t.subtrees(1))
    else None
  
  object P {
    def unapply(t: Term): Option[(String, Term, List[Term])] = {
      if (t.isLeaf) {
          Some(("LEAF",t,null))
          //if (typeOf_!(t).isLeaf || (ctx.vars contains t) || t.root.literal.isInstanceOf[Int]) t
          //else TypedTerm(t, scalar)
      }
      else if (t =~ ("|!", 2)) { 
        //Guarded Expr
        val v = t.subtrees(0);
        val cond = t.subtrees(1);
        Some(("GUARD",cond,List(v)))
      }
      else if (t =~ ("<", 2) || t =~ ("<₁", 2) || t =~ ("<₂", 2)) {     // Oh the subscripts are pretty damn hackish
        //LT Expr
        Some(("LT",t.subtrees(0),List(t.subtrees(1))))
      }
      else if (t =~ ("∧", 2)) { 
        //AND Expr
        Some(("AND",t.subtrees(0),List(t.subtrees(1))))
      }
      else if (t =~ ("∨", 2)) { 
        //OR Expr
        Some(("OR",t.subtrees(0),List(t.subtrees(1))))
      }
      else if (t =~ ("program", 1)){//higher level program
        unapply(t.subtrees(0))
      }
      else if (t =~ ("fix", 1)){//fixed point
        Some(("FIX",t.subtrees(0),null))
      }
      else if (t =~ (":", 2)){
        //Just a label, ignore LHS
        if (t.subtrees(0).root.literal.toString() == "bazinga")
          Some(("BAZINGA",t.subtrees(0).subtrees(0),List(t.subtrees(1))))
        else unapply(t.subtrees(1))
        
      }
      else if (isInterval(t.root) && t.subtrees.size == 1){
        Some(("IN_INTERVAL",T(t.root), List(t.subtrees(0))))
      }
      else{
        isSlash(t) match{
          case Some(slashes) => Some(("SLASH",null,slashes))
          case _ =>
            isApp(t) match {
              case Some((f, args)) =>
                Some(("APPLY",f, args))
              case _ =>
                isAbs(t) match {
                  case Some((vars, body)) => 
                    Some(("MAPSTO",body, vars))
                  case _ =>
                    Some(("NONE END:  " + t.root.literal.toString() + "|" +t.subtrees.size.toString()),null,null)
                }
            }
        }
      }
    }
  }
  
  
  object ListP {
    def unapply(t: Term) = {
      ConsPod.`⟨ ⟩?`(t)
    }
  }
  case class Context (
      val inputArray: Identifier, 
      val fixVar: Option[Identifier], 
      val insideFix: Boolean = false, 
      val localVars : List[Term] = List(), 
      val tmpVar: String = "" 
      ) {
    def inp (i: Identifier) = {
        Context(i, fixVar, insideFix,localVars,tmpVar) 
      }  
    def fix(v: Identifier) = {
      assert(insideFix && fixVar.isEmpty)
      Context(inputArray, Some(v), insideFix,localVars,tmpVar) 
    }
    def setFix = {
      assert(! insideFix)
      Context(inputArray, fixVar, true,localVars,tmpVar) 
    }
    def + (i: Term) = {
      Context(inputArray, fixVar, insideFix,localVars :+ i,tmpVar) 
    }
    def ++ (l: List[Term]) = {
      Context(inputArray, fixVar, insideFix,localVars ++ l,tmpVar) 
    }
    def tmp (s: String) = {
      Context(inputArray, fixVar, insideFix,localVars,s) 
    }
  }
  
  def isRoutine(i: Identifier)= (i.literal.toString().indexOf('[') >= 0)
  
  def isScalar(v: Term) = (typeOf_!(v).isLeaf)
  
  def isInterval(i: Identifier)= i.kind == "set"
  
  def toInterval(typ: Term) = {
    Interval(typ.leaf.literal.toString)
  }
  
  def toVar(t:Term) = {
    Var(t.leaf.literal.toString,toInterval(typeOf_!(t)))
  }
  
  def FormulaToExpr(ff:Term) (implicit ctx: Context) : Expr = {
    ff match {
      case P("LEAF",t:Term,null) =>
          val v = t.root.literal.toString
          if (t.root.kind == "variable" && typeOf_!(t).isLeaf) Var(v,Interval((typeOf_!(t).leaf).literal.toString()))
          else if (isInterval(t.leaf)) Interval(v)
          else if (v.matches("[+-]?\\d+")) Num(Integer.parseInt(v))
          else throw new Exception("Leaf not analyzed: " + t.toString())
      case P("GUARD",cond:Term,List(v:Term)) =>
        Guarded(FormulaToExpr(cond),FormulaToExpr(v))
      case P("LT",a:Term, List(b:Term)) =>
        FunApp("<",List(FormulaToExpr(a),FormulaToExpr(b)))
      case P("AND",a:Term, List(b:Term)) =>
        FunApp("&&",List(FormulaToExpr(a),FormulaToExpr(b)))
      case P("OR",a:Term, List(b:Term)) =>
        FunApp("||",List(FormulaToExpr(a),FormulaToExpr(b)))
      case P("FIX",t:Term,null) =>
        ???
        //FunApp(FuncPre("FIX"),List(FormulaToExpr(t)))
      case ListP(elts) =>
        FunApp("[]",(elts map FormulaToExpr))
      case P("APPLY",f,args) if (f.isLeaf)=>
        if (ctx.inputArray == f.root || ctx.fixVar == Some(f.root)){
          MemRead(ctx.inputArray.literal.toString,(args map FormulaToExpr))
        }
        else FunApp(f.root.literal.toString, (args map FormulaToExpr))
      case P("MAPSTO",body,vars) =>
        throw new Exception("MAPSTO in Expr")
        //val vs = (vars map FormulaToExpr);
        //FuncDef(vs,FormulaToExpr(body))
      case P("BAZINGA",baz,List(e)) =>
        ???
      case P("SLASH",null,slashes) =>
        val isFunc = (typeOf_!(ff).isLeaf);
        Slash(isFunc,(slashes map FormulaToExpr))
      case P("IN_INTERVAL",interval,List(i)) =>
        val intv = interval.root.literal.toString();
        FunApp("In", List(Interval(intv),FormulaToExpr(i)))
      case _ =>
        throw new Exception("Not analyzed Term: " + ff.root.literal.toString() + "|" + ff.subtrees.size.toString() )
    }
  }
  var tmpCtr = 0
  
  val minFn = Prelude.min
  val maxFn = Prelude.max
  
  val reduceFns = List(minFn,maxFn)
  val reduceFnsStr = List("min","max")
  /* Predicates in Conditions affecting Bounds */

  def getAndPreds(expr: Expr): List[Expr] = {
     expr match {
      case FunApp("&&",List(p1,p2)) => List.concat(getAndPreds(p1), getAndPreds(p2))
      case _ => List(expr)
    }
  }
  def getOrPreds(expr: Expr): List[Expr] = {
     expr match {
      case FunApp("||",List(p1,p2)) => List.concat(getOrPreds(p1), getOrPreds(p2))
      case _ => List(expr)
    }
  }
  def mLower(l1:Expr,l2:Expr) = {
    (l1,l2) match {
      case (null,l2) => l2
      case (l1,null) => l1
      case (l1,l2) => FunApp("max",List(l1,l2))
    }
  }
  def mUpper(u1:Expr,u2:Expr) = {
    (u1,u2) match {
      case (null,u2) => u2
      case (u1,null) => u1
      case (u1,u2) => FunApp("min",List(u1,u2))
    }
  }
  def mergeBounds(b: ((Expr,Expr), (Expr,Expr))): (Expr,Expr) = {
    b match {
      case ((l1,u1),(l2,u2)) => (mLower(l1,l2),mUpper(u1,u2))
      case _ => ??? //Error Handling, shouldn't happen
    }
  }
  def getBoundsFromPred(e:Expr,varStr:String) :(Expr,Expr) = {
    e match {
      case FunApp("<",List(Var(i,inti), erhs))if i == varStr => (null,erhs)
      case FunApp("<",List(elhs,Var(i,inti))) if i == varStr=> (FunApp("+",List(elhs,Num(1))),null)
      case FunApp("In",List(Interval(intv), Var(i,_))) if (i == varStr)=> 
        (GetBound(Interval(intv),LOWER),GetBound(Interval(intv),UPPER))
      case _ => (null,null)
        
    }
  }
  def crossBounds (xs:List[(Expr,Expr)],ys:List[(Expr,Expr)]): List[((Expr,Expr),(Expr,Expr))] = {
    var res:List[((Expr,Expr),(Expr,Expr))] = List()
    for (x <- xs){
      for(y <- ys){
        res = res :+ (x,y)
      }
    }
    res
  }
  
  def mergeSplitBounds(b1s: List[(Expr,Expr)],b2s: List[(Expr,Expr)]) : List[(Expr,Expr)] = {
    crossBounds(b1s, b2s) map   mergeBounds
  }
  def getCNFBounds(expr:Expr, i:String): List[List[(Expr,Expr)]] = {
    val ands = getAndPreds(expr)  
    val cnf = ands.map(getOrPreds)
    cnf  map (l => l map ( e => getBoundsFromPred(e,i)))
  }
  
  def getSplitBounds(expr:Expr,i:String) : List[(Expr,Expr)] = {
    val cnfBounds = getCNFBounds(expr,i)
    cnfBounds reduceLeft mergeSplitBounds
  }
  
  def liftLoops(ff:Term)(implicit ctx:Context) = {
    //find all loops that are at the top level
    //compute definitionStmt and computationStmt for them
    //replace them with appropriate tmp var
    //return {definitionStmt;computationStmt}* block
    //TODO: WHEN can I say that I've found a subtree that is independent and should be computed with a temporary?
    val internals = ff.nodes collect {   
      case st@P("APPLY",f,List(P("MAPSTO",body,vars))) if (reduceFns contains f)
        =>
          //found one
          
          if (vars.size == 1){
            tmpCtr = tmpCtr +1
            val tmpStr = "tmp" + tmpCtr.toString
            val ivlVar = Interval(typeOf_!(vars(0)).leaf.literal.toString)
            val ivlBody = Interval(typeOf_!(body).leaf.literal.toString)
            val forStmt = For(
                vars(0).root.literal.toString,
                GetBound(ivlVar,LOWER),
                GetBound(ivlVar,UPPER),
                FWD,
                Assign(tmpStr,FunApp(f.root.literal.toString, List(
                                    Var(tmpStr,ivlBody),
                                    FormulaToExpr(body)
            )))) 
            val initVar = Var(s"INIT${f.root.literal}".toUpperCase,ivlBody)
            val blk = Block(List(DefVar(tmpStr, "int"), Assign(tmpStr, initVar),forStmt )) //TODO: 
            ((st,T($TV(tmpStr).root,List())),blk)
          }
          else ???
    }
    
    (TypedTerm.replaceDescendants(ff,internals map (_._1) toList)(new Scope) //TODO: use the actual scope from JSON
        ,simplifyStmtN(5,(Block(internals map (_._2) toList))))
    //simplifyStmt(Block((ff.subtrees map (t => InternalSub(t,ff)))))
  }
 
  
  def FormulaToStmt(ff: Term) (implicit ctx: Context): Stmt = {
    //println(ff.toPretty); println(ctx)
    ff match {
      case P("LEAF", t, null) =>
        if (t.root == ctx.inputArray) {
          Block(List())
        }
        else {
          println(t)
          println(ctx.inputArray)
          ???
        }
      case P("GUARD", cond, List(v)) =>
        If(FormulaToExpr(cond),FormulaToStmt(v),Block(List()))
      case P("FIX", t, null) =>
        if (ctx.fixVar.isDefined) throw new Exception("FixVar found already")
        else FormulaToStmt(t)(ctx.setFix)
      case T(`@:`.root, List(T(`↦`.root, List(va, body)), arg)) =>
          if (va.leaf.literal.toString().startsWith("?")){ //
            FormulaToStmt(body)
          }
          else
            Block(List(FormulaToStmt(arg),
                FormulaToStmt(body)(ctx inp va.leaf) )) 

      case P("APPLY",f,args) =>
        if (f.isLeaf && isRoutine(f.leaf)) {
          //A[I,J] etc
          val style = "rec";
          val name :: params = f.root.literal.toString().split(raw"[\[,\]]").toList;
          FunctionCall(s"func${name}_${style}",(params map (Interval(_))))
        }
        else {
          f match {
            case P("SLASH",null,quads) => 
              Block(quads map (q => q :@(args) ) map FormulaToStmt)
            case _ => ???
          }
        }
      case P("MAPSTO",body,vars) =>
        //has to become a for loop, for on variables of MAPSTO - fixVar if its there
        //all variables must be scalar types
        val (loopvars,ctxWFix) = 
          if (ctx.insideFix && ctx.fixVar.isEmpty) 
            (vars.tail,ctx fix vars.head.leaf)
          else
            (vars,ctx)
        loopvars foreach (v => if (! isScalar(v)) 
          throw new Exception("expected scalar variable"))
        val newCtx =  ctxWFix ++ loopvars
        if (! isScalar(body)) FormulaToStmt(body)(newCtx)
        else {
        //check if body has other loops(only immediate ones), if yes,
        //replace those loops with tmp in the expression tree and get Stmts (using FormulaToStmt) for tmp computation
          val (newBody,tmpStmt) = liftLoops(body)
          val initOrig = MemWrite(ctx.inputArray.literal.toString,
              (newCtx.localVars map toVar),
              FormulaToExpr(newBody)(newCtx))
          val init: Stmt = tmpStmt match {
              case Block(List()) => initOrig
              case _ => Block(List(tmpStmt,initOrig))
              }
          (loopvars :\ init) { (v,inner) => 
              val iV = Interval(typeOf_!(v).leaf.literal.toString)
              For(v.leaf.literal.toString,
                  GetBound(iV,LOWER),
                  GetBound(iV,UPPER),
                  FWD, //TODO: get the right direction from Term
                  inner)
          }
        }
      case P("BAZINGA",baz,List(e)) =>
        Parallel(Integer.parseInt(baz.root.literal.toString),FormulaToStmt(e))
      case P("SLASH",null,quads) =>
        Block((quads map FormulaToStmt))
      case P("IN_INTERVAL",t,List(v)) =>
        ???
      case _ =>
        throw new Exception("Not analyzed Term: " + ff.root.literal.toString() + "|" + ff.subtrees.size.toString() )
    }
  }
  
  def localIntervalDefs(argIntervals: List[Interval])(implicit scope: Scope) : Stmt = {
    val argIntervalIds = argIntervals map (i => S(i.name))
    val unions =
      scope.sorts.mastersHie flatMap { 
      case T(master, List(T(lower, _), T(upper, _)))  if !(argIntervalIds contains master) && 
                                                          (argIntervalIds contains lower) && 
                                                          (argIntervalIds contains upper) =>
        println(s"${master}  ${argIntervalIds.head}")
        println(s"${master}   ${upper}   ${lower}")
        List(DefIntervalUnion(Interval(master.literal.toString), (Interval(lower.literal.toString), (Interval(upper.literal.toString)))))
      case _ => List()
      }
    val splits =
      argIntervals flatMap { interval =>
        val hie = scope.sorts.findSortHie(new Identifier(interval.name)).getOrElse { throw new TranslationError(s"cannot find type '${interval.name}'") }
        hie.subtrees match {
          case x@List(lower, upper) =>
            x zip List(LOWER, UPPER) map { case (s, w) =>
              DefIntervalSplit(Interval(s.root.literal.toString), interval, w)
            }
          case _ => List()
        }
      }
    Block(unions ++ splits)
  }
  
  implicit class StmtConcat(val stmt: Stmt) extends AnyVal {
    def toList =  stmt match { case Block(stmts) => stmts   case _ => List(stmt) }
    def toBlock = stmt match { case b@Block(_)   => b       case _ => Block(List(stmt)) }
    def ++(next: Stmt) = Block(toList ++ next.toList)
  }
  
  val ↦ = TI("↦")
  val `:` = TI(":")
  
  def generateBaseCase(name: String,style: String,argIntervals: List[Interval],elseBranch : Stmt) = {
    If(FunApp("BASE_CONSTRAINT",argIntervals),FunctionCall(s"func${name}_${style}",argIntervals),elseBranch)
  }
  
  def FormulaToFunction(name: String,style: String, argIntervals: List[Interval], ff:Term)(implicit scope: Scope) : FunDef = {
    val localDefs = localIntervalDefs(argIntervals)
    val blockWOCilk = FormulaToFunction(ff)
    val block = blockWOCilk match {
      case Block(l) => Block(cilkParallelize(l))
      case _ => blockWOCilk
    }
    val body = localDefs ++ block
    FunDef(s"func${name}_${style}", argIntervals, (if (style == "rec") generateBaseCase(name,"loop",argIntervals,body) else body).toBlock)
  }
  
  def FormulaToFunction(ff: Term) : Stmt = {
    //find inputArray
    ff match {
      case T(`↦`.root,List(v,body)) =>
        simplifyStmtN(5,FormulaToStmt(body)(Context(v.leaf,None)))
      case T(Prelude.program.root,List(body)) => 
        FormulaToFunction(body)
      case T(`:`.root,List(label,body)) =>
        FormulaToFunction(body)
      case _ => throw new Exception("Cannot find inputArray at the top level")
   }
  }
  
  def stripColons(t:Term): Term = {
    if (t =~ (":",2) && t.subtrees(0).root != "bazinga") {
      //println(t.subtrees(0).root.literal.toString)
      stripColons(t.subtrees(1))
    }
    else TypedTerm.preserve(t, T(t.root,t.subtrees map stripColons)) 
  }
  def cilkParallelize(l: List[Stmt]) : List[Stmt]= {
    l match {
      case Nil => List()
      case stmt :: rest => 
        stmt match {
          case Parallel(i, s) =>
            //Find all Parallel statements from rest that are level i and put them in a Fork
            var parsi : List[Stmt] = List(s)
            var newrest : List[Stmt] = List()
            for (pstmt <- rest){
              pstmt match {
                case Parallel(iprime,sprime) if (i == iprime) => 
                  parsi = parsi :+ sprime
                case _ => 
                  newrest = newrest :+ pstmt
              }
            }
            Fork(parsi) ::  cilkParallelize(newrest)
          case _ => 
            stmt :: cilkParallelize(rest)
        }
    }
  }
  def simplifyStmtN(n:Int,stmt:Stmt): Stmt = {
    Function.chain(List.fill(n)(simplifyStmt _))(stmt)
  }
  def simplifyStmt(stmt: Stmt): Stmt = {
    stmt match {
      case Block(l) => 
        //look at each child - if its a Block itself just lift it up
        val blockList = l map simplifyStmt flatMap (_.toList)
        if (blockList.size == 1) blockList.head
        else Block(blockList)
      case Fork(l) => 
        //look at each child - if its a Block itself just lift it up
        val blockList = l map simplifyStmt flatMap (_.toList)
        if (blockList.size == 1) blockList.head
        else Block(blockList)
      case Parallel(i, s) => Parallel(i, simplifyStmt(s))
      case For(i,lb,ub,dir,If(cond,caseThen,Block(List()))) => 
        val spBounds = getSplitBounds(cond,i) map (lu => mergeBounds((lu,(lb,ub))))
        if (spBounds.length == 1){ 
          For(i,spBounds.head._1,spBounds.head._2,dir,simplifyStmt(caseThen))
        }else{
          Block(spBounds map (b => For(i,b._1,b._2,dir,simplifyStmt(caseThen)) ))
        }
      case For(v,lb,ub,dir,Block(stmts :+ MemWrite(an,indices,Guarded(cond,e)))) =>
        For(v,lb,ub,dir,If(cond,Block(stmts :+ MemWrite(an,indices,e)),Block(List())))
      case For(v,lb,ub,dir,ss) =>   For(v, lb, ub, dir, simplifyStmt(ss))
      case If(cond,caseThen,caseElse) => If(cond, simplifyStmt(caseThen), simplifyStmt(caseElse))
      case Assign(v1,FunApp(f,List(Var(v2,ii),Guarded(cond,e)))) if ((v1 == v2) &&  (reduceFnsStr contains f))=>
        If(cond,Assign(v1,FunApp(f,List(Var(v2,ii),e))),Block(List()))
      
      /* If last stmt of a for loop is array write then lift the guard*/
      /*case MemWrite(arrayName,indices,rhs) =>
        rhs match {
          case FuncPre(f) =>
            if (f==arrayName) Block(List())
            else stmt
          case _ => stmt
        }*/
      case _ => stmt
    }
  }
  
  
  
  def main(args: Array[String]) {
    
    //val e = E(MemRead("dist"),List(E(Var("i")),E(Var("j"))))
    //println(e)
    //implicit val scope = examples.Paren.scope
    //println(scope)
    val cliOpts = new CommandLineConfig(args toList)
    val outf = new BufferedWriter(new FileWriter (cliOpts.outputfile()))
    CppOutput.writePrefaceTo(outf)

    import syntax.Nullable._
    
    for (filename <- cliOpts.filenames()){
      val f = new BufferedReader( new FileReader(filename))
      val blocks = CLI.getBlocks(f)
      for (block <- blocks){
        val json = JSON.parse(block).asInstanceOf[BasicDBObject]
        implicit val scope = json.get("scope") andThen_ (Scope.fromJson, examples.Paren.scope)
            //  { throw new SerializationError("scope not found", json) })  // TODO change to "throw" when all JSONs contain proper scope
        val prg = json.get("term")
        val style = json.getString("style") 
        val title = json.getString("program")
        val r = """(.*)\[(.*)\]$""".r
        val x = r.findFirstMatchIn(title)
        val name = x.get.group(1) 
        val arg_intervals = x.get.group(2).split(",").toList map (a => Interval(a))
        println(name)
        println(arg_intervals)
        if (prg != null){
          val ff = Formula.fromJson(prg.asInstanceOf[BasicDBObject])
          println(s"The program is: ${ff.toPretty}")
          val ffwnocolons = stripColons(ff)
          println(ffwnocolons.toPretty)
          val fundef = FormulaToFunction(name,style,arg_intervals,ffwnocolons)
          println(s"The program AST is: ")
          println(fundef)
          val nl = new NestedListTextFormat[String]("  ","  ")()
          nl.layOut(fundef.body.toPrettyTree)
          println("\nThe code is:")
          val cppGen = new codegen.CppOutput
          val code = cppGen(fundef,0)
          println(code)
          
          outf.write(code + "\n")
        }
        else{
          println(s"The program is: null")
        }
      }
    }
    outf.close()
  }
}