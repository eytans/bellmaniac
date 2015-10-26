// Generated by LiveScript 1.4.0
(function(){
  var spawn, assert, _, root, splitTextToBlocks;
  spawn = require('child_process').spawn;
  assert = require('assert');
  _ = require('lodash');
  root = typeof exports != 'undefined' && exports !== null ? exports : this;
  splitTextToBlocks = function(input){
    return input.split(/\n+(?!\s)/).filter((function(it){
      return /\S/.exec(it);
    }));
  };
  root.bellmaniaParse = function(input, success, error){
    var blocks, buffer, output, jar, toStream, stream, err;
    blocks = splitTextToBlocks(input);
    try {
      buffer = [];
      output = {
        fromNearley: [],
        fromJar: []
      };
      jar = spawn("java", ['-jar', 'lib/bell.jar', '-']);
      jar.stdout.setEncoding('utf-8');
      jar.stdout.on('data', function(data){
        buffer.push(data);
      });
      jar.stdout.on('end', function(){
        var i$, ref$, len$, block, outputBlock, err;
        try {
          for (i$ = 0, len$ = (ref$ = buffer.join("").split(/\n\n+(?=\S)/)).length; i$ < len$; ++i$) {
            block = ref$[i$];
            outputBlock = JSON.parse(block);
            output.fromJar.push({
              value: outputBlock
            });
          }
          success(output);
        } catch (e$) {
          err = e$;
          console.log(err);
          error(err);
        }
      });
      jar.stderr.on('data', function(data){
        error(data);
      });
      root.scope = [];
      output.fromNearley = _.chain(blocks).map(function(block){
        var p, parsed, results;
        p = new nearley.Parser(grammar.ParserRules, grammar.ParserStart);
        parsed = p.feed(block);
        results = _.filter(parsed.results, function(r){
          return r;
        });
        assert(results.length > 0, "No possible parse of input found.");
        assert(results.length === 1, JSON.stringify(results) + " is not a unique parse.");
        return results[0];
      }).filter(function(block){
        return block.root.kind !== 'set';
      }).map(function(block){
        return {
          check: block
        };
      }).value();
      toStream = function(stream){
        var i$, ref$, len$, parsedBlock;
        for (i$ = 0, len$ = (ref$ = output.fromNearley).length; i$ < len$; ++i$) {
          parsedBlock = ref$[i$];
          stream.write(JSON.stringify(parsedBlock));
          stream.write("\n\n");
        }
        return stream.end();
      };
      fs.writeFileSync("/tmp/synopsis.txt", input);
      stream = fs.createWriteStream("/tmp/synopsis.json");
      stream.once('open', function(){
        return toStream(stream);
      });
      jar.stdin.setEncoding('utf-8');
      return toStream(jar.stdin);
    } catch (e$) {
      err = e$;
      return error(err);
    }
  };
}).call(this);
