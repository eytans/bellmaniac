object Parenthesis {
  import Prelude._
  import java.io.{PrintStream, File}
  implicit def int2expr(i: Int) = Const(i)

  val w = Var("w", 3)
  val x = Var("x", 1)
  
  val c = Var("c", 2)
  val par = Algorithm(c, i :: j :: Nil,
    0 <= i and i < n and i < j and j < n,
    IF ((i === j-1) -> x(i)) 
    ELSE
      Reduce(c(i, k) + c(k, j) + w(i, k, j) 
      where k in Range(i+1, j)))
   
  def main(args: Array[String]) {
    SMT.open()
    val proof = new Proof()
    import proof._
   
    input(w)
    input(x)
    input(n)
    add(par)
    induction(par, j-i, 1)

    val r = Var("r", 2)
    val R = Algorithm(r, List(i, j), par.pre, IF ((i === j-1) -> x(i)) ELSE Zero)
    add(R)

    val par0 = manual($, 
      Op(Reduce(c(i, k) + c(k, j) + w(i, k, j) where k in Range(i+1, j)), r(i, j)),
      $$.unfold($, R))(par)

    val c0 = (introduce($, n, w, r) andThen 
      selfRefine("c0"))(par0)    
    val List(c1, c000, c001, c011) = split("c1", n < 4, i < n/2, j < n/2)(c0) 
  
    val c100 = rewrite("c100", c0)(n -> n/2)(c000) 
    // use free assumption n mod 2 = 0    
    val c111 = rewrite("c111", c0)(
        i->(i-n/2), 
        j->(j-n/2), 
        n->n/2, 
        w->(w>>(n/2,n/2,n/2)), 
        r->(r>>(n/2,n/2))
      )(c011)

    // We have to make a very general version of b0 to make proofs work
    val s = Var("s", 2)
    val t = Var("t", 2)
    val w1 = Var("w1", 3)
    val b0 = (unfold($, c0) andThen
      splitRange($, k, n/2) andThen
      specialize($, c0) andThen
      genApp($, c000.v, s) andThen 
      genApp($, c011.v, t) andThen
      genApp($, w, w1) andThen
      selfRefine("b0"))(c001)

    val List(b1, b000, b001, b010, b011) = split("b1", n < 4, i < n/4, j < n/2+n/4)(b0)
       
    val b110 = rewrite("b110", b0)(
        i->(i-n/4),
        j->(j-n/4),
        n->n/2,
        w->(w>>(n/4,n/4,n/4)),
        w1->(w1>>(n/4,n/4,n/4)),
        s->(s>>(n/4,n/4)),       
        t->(t>>(n/4,n/4)),
        r->(r>>(n/4,n/4))
      )(b010)

    // define d
    val d = Var("d", 7)
    val D = Algorithm(d, List(i, j, n, w, r, s, t), 0 <= i and i < n/2 and 0 <= j and j < n/2,
      Op(Reduce(s(i, k) + t(k, j) + w(i, k, j) where k in Range(0, n/2)), r(i, j)))
    add(D)
     
    val bij = b0.capture(2) 

    // we can infer i and j displacements by looking at pre-condition of b0 and case of b000   
    val b100 = rewrite("b100", b0, $$.splitRange($, Var("k1"), n/4), $$.unfold($, D))(
        i->i,
        j->(j-n/4),
        n->n/2,        
        w->(w>>(0,n/4,n/4)),
        w1->(w1>>(0,0,n/4)),        
        t->(t>>(n/4,n/4)),
        r->D.gen(2)(i, j-n/4, n/2, w1>>(0,n/4,n/2), 
          r>>(0,n/2),s>>(0,n/4),bij>>(n/4,n/2))          
      )(b000)
    val b200 = specialize("b200", b0)(b100)
    val b111 = rewrite("b111", b0, $$.splitRange($, Var("k2"), n/4+n/2), $$.unfold($, D))(
        i->(i-n/4),
        j->(j-n/2),
        s->(s>>(n/4,n/4)),
        t->(t>>(n/2,n/2)),
        w->(w>>(n/4,n/2,n/2)),
        w1->(w1>>(n/4,n/4,n/2)),
        n->n/2,
        r->D.gen(2)(i, j-n/4, n/2, w>>(n/4,n/2,n/2+n/4),
          r>>(n/4,n/2+n/4), bij>>(n/4,n/2), t>>(n/2,n/2+n/4))
      )(b011)
    val b211 = specialize("b211", b0)(b111)
    val b101 = rewrite("b101", b0, 
        $$.splitRange($, Var("k1"), n/4) andThen $$.splitRange($, Var("k2"), n/4+n/2),
        $$.unfold($, D) andThen $$.unfold($, D))(
        j->(j-n/2),
        n->n/2,
        s->s,
        w1->(w1>>(0,0,n/2)),
        t->(t>>(n/2,n/2)),
        w->(w>>(0,n/2,n/2)),
        r->D.gen(2)(i, j-n/4, n/2, w1>>(0,n/4,n/2+n/4),
          D.gen(2)(i, j, n/2, w>>(0,n/2,n/2+n/4), r>>(0,n/2+n/4), bij>>(0,n/2), t>>(n/2,n/2+n/4)), 
          s>>(0,n/4), bij>>(n/4,n/2+n/4))        
      )(b001)
    val b201 = specialize("b201", b0)(b101)

    val List(d1, d00, d01, d10, d11) = split("d1", n < 4, i < n/4, j < n/4)(D)
    val d100 = rewrite("d100", D, $$.splitRange($, k, n/4), $$.unfold($, D))(
      n->n/2, 
      r->D.gen(2)(i,j,n/2,w>>(0,n/4,0),r,s>>(0,n/4),t>>(n/4,0))
    )(d00)
    val d110 = rewrite("d110", D, $$.splitRange($, k, n/4), $$.unfold($, D))(
      i->(i-n/4), n->n/2, 
      r->D.gen(2)(i, j, n/2, w>>(n/4,0,0), r>>(n/4,0), s>>(n/4,0), t),
      w->(w>>(n/4,n/4,0)),
      s->(s>>(n/4,n/4)),
      t->(t>>(n/4,0))
    )(d10)
    val d101 = rewrite("d101", D, $$.splitRange($, k, n/4), $$.unfold($, D))(
      j->(j-n/4), n->n/2,
      r->D.gen(2)(i, j, n/2, w>>(0,0,n/4), r>>(0,n/4), s, t>>(0, n/4)),
      w->(w>>(0,n/4,n/4)),
      s->(s>>(0,n/4)),
      t->(t>>(n/4,n/4))
    )(d01)
    val d111 = rewrite("d111", D, $$.splitRange($, k, n/4), $$.unfold($, D))(
      i->(i-n/4), j->(j-n/4), n->n/2,
      r->D.gen(2)(i, j, n/2, w>>(n/4,0,n/4), r>>(n/4,n/4), s>>(n/4,0), t>>(0,n/4)),
      w->(w>>(n/4,n/4,n/4)),
      s->(s>>(n/4,n/4)),
      t->(t>>(n/4,n/4))
    )(d11)

    val py = new NumPython(2)    
    compile(par, new PrintStream(new File("paren.py")), py)
    SMT.close()
  }
}

