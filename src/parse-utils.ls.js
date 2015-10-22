// Generated by LiveScript 1.4.0
(function(){
  var root;
  root = typeof exports != 'undefined' && exports !== null ? exports : this;
  root.scope = [];
  root.id = function(d){
    return d && d[0];
  };
  root.take = function(index){
    return function(d){
      return d && d[index];
    };
  };
  root.keywords = ["set", "fix", "/", "+", "×", "∩", "-", "*"];
  root.tree = function(root, subtrees){
    return {
      $: 'Tree',
      root: root,
      subtrees: subtrees
    };
  };
  root.identifier = function(literal, kind){
    return {
      $: 'Identifier',
      literal: literal,
      kind: kind
    };
  };
  root.operator = function(literal){
    return identifier(literal, 'operator');
  };
  root.genericIdentifier = function(literal){
    return identifier(literal, '?');
  };
  root.declareSet = function(literal){
    var newSet;
    if (root.keywords.indexOf(literal) === -1) {
      newSet = tree(identifier(literal, 'set'), []);
      root.scope.push(newSet);
      return newSet;
    } else {
      return false;
    }
  };
  root.declareSets = function(head, tail){
    var i$, ref$, len$, literal, newSet;
    for (i$ = 0, len$ = (ref$ = [head].concat(tail)).length; i$ < len$; ++i$) {
      literal = ref$[i$];
      if (!(newSet = root.declareSet(literal))) {
        return false;
      }
    }
    return newSet;
  };
  root.typeVariable = function(literal){
    if (root.keywords.indexOf(literal) > -1) {
      return false;
    } else if (root.scope.filter(function(set){
      return set.root.literal === literal;
    }).length > 0) {
      return tree(identifier(literal, 'set'), []);
    } else {
      return tree(identifier(literal, 'type variable'), []);
    }
  };
  root.variable = function(literal){
    if (root.keywords.indexOf(literal) === -1 && root.scope.filter(function(set){
      return set.root.literal === literal;
    }).length === 0) {
      return tree(identifier(literal, 'variable'), []);
    } else {
      return false;
    }
  };
  root.abstraction = function(par, body){
    return par && body && tree(genericIdentifier('↦'), [par, body]);
  };
  root.application = function(lhs, rhs){
    return lhs && rhs && tree(genericIdentifier('@'), [lhs, rhs]);
  };
  root.typeOperation = function(op, lhs, rhs){
    return op && lhs && rhs && tree(operator(op), [lhs, rhs]);
  };
  root.slashExpression = function(lhs, rhs){
    return lhs && rhs && tree(operator('/'), [lhs, rhs]);
  };
  root.fixedExpression = function(subj){
    return subj && tree(operator('fix'), [subj]);
  };
  root.cons = function(car, cdr){
    return application(application(variable('cons'), car), cdr);
  };
}).call(this);
