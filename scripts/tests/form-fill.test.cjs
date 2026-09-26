const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const helper = fs.readFileSync('app/src/main/assets/form-fill.js', 'utf8');
function page(definitions, overrides = {}) {
  const messages = [], events = [], timers = [];
  const bridge = {postMessage: m => messages.push(JSON.parse(m))};
  class Input { constructor(options) { Object.assign(this, {type:'text', autocomplete:'', name:'', id:'', isConnected:true, disabled:false, readOnly:false}, options); }
    getClientRects() { return this.hidden ? [] : [this.rect || {width:100,height:20,top:0,left:0,right:100,bottom:20}]; } closest() { return null; }
    dispatchEvent(event) { events.push([this.id, event.type]); }
  }
  Object.defineProperty(Input.prototype, 'value', {set(v) {this.filled = v;}});
  const forms = definitions.map(fields => {const form = {}; form.fields = fields.map(f => new Input(f)); form.fields.forEach(f => f.form = form); form.querySelectorAll = () => form.fields; return form;});
  const window = {innerHeight:600,innerWidth:800,isSecureContext:true, bridge, addEventListener(){}}; window.top = window;
  const context = {window, location:{href:'https://example.com/login',origin:'https://example.com'}, URL, document:{forms, documentElement:{}, addEventListener(){},removeEventListener(){}}, HTMLInputElement:Input,
    getComputedStyle:e=>({visibility:'visible',display:'block',opacity:e.opacity || '1'}), MutationObserver:class {observe(){} disconnect(){}}, Event:class {constructor(type){this.type=type;}}, setTimeout:f => {timers.push(f);return timers.length;}, clearTimeout(){}, ...overrides};
  vm.runInNewContext(helper + '("bridge","nonce","https://example.com/login")', context);
  return {messages, events, forms, bridge, context, timers};
}
const user = {id:'user',autocomplete:'username'}, pass = {id:'pass',type:'password',autocomplete:'current-password'};
test('hints outrank heuristics, only selected fields filled, events without submit',()=>{
 const p=page([[{id:'email',type:'email'},user,pass,{id:'hidden',type:'hidden'}]]);
 assert.equal(p.messages.length,1);
 p.bridge.onmessage({data:JSON.stringify({nonce:'nonce',username:'u',password:'p'})});
 assert.equal(p.forms[0].fields[0].filled,undefined);
 assert.equal(p.forms[0].fields[1].filled,'u'); assert.equal(p.forms[0].fields[2].filled,'p');
 assert.equal(p.events.length,4); assert.equal(p.bridge.onmessage,null);
});
test('zero, multiple, hidden, disabled, read-only, signup and ambiguous users do not request',()=>{
 for(const forms of [[],[[user]],[[user,pass],[user,pass]],[[{...user,hidden:true},pass]],[[user,{...pass,disabled:true}]],[[user,{...pass,readOnly:true}]],[[user,{...pass,autocomplete:'new-password'}]],[[user,{...user,id:'other'},pass]]]) assert.equal(page(forms).messages.length,0);
});
test('heuristics find email and conservative username names',()=>{
 for(const field of [{type:'email'},{name:'username'},{name:'login'}]) assert.equal(page([[field,pass]]).messages.length,1);
 assert.equal(page([[{name:'search'},pass]]).messages.length,0);
});
test('nonce mismatch or changed candidate prevents filling',()=>{
 for(const mutation of [p=>p.forms[0].fields[1].disabled=true,p=>p.context.location.origin='https://other.example']) {
 const p=page([[user,pass]]); mutation(p);p.bridge.onmessage({data:JSON.stringify({nonce:'nonce',username:'u',password:'p'})});assert.equal(p.events.length,0);
 }
 const p=page([[user,pass]]);p.bridge.onmessage({data:JSON.stringify({nonce:'wrong',username:'u',password:'p'})});assert.equal(p.events.length,0);
});
test('subframes and insecure contexts never request',()=>{
 const w={isSecureContext:true,top:{}}; assert.equal(page([[user,pass]],{window:w}).messages.length,0);
 const insecure={isSecureContext:false}; insecure.top=insecure;assert.equal(page([[user,pass]],{window:insecure}).messages.length,0);
});
test('observation timeout and no-credential reply tear down without filling',()=>{
 const empty=page([]);empty.timers[0]();assert.equal(empty.bridge.onmessage,null);
 const p=page([[user,pass]]);p.bridge.onmessage({data:JSON.stringify({nonce:'nonce'})});assert.equal(p.events.length,0);assert.equal(p.bridge.onmessage,null);
});

test('same-document URL changes and passkey autocomplete tokens retain the candidate',()=>{
 const p=page([[{id:'account',autocomplete:'section-login username webauthn'},pass]]);
 assert.equal(p.messages.length,1);p.context.location.href='https://example.com/#/login';
 p.bridge.onmessage({data:JSON.stringify({nonce:'nonce',username:'u',password:'p'})});assert.equal(p.events.length,4);
});

test('zero size, offscreen and transparent inputs do not request',()=>{
 for(const changes of [{rect:{width:0,height:0,top:0,left:0,right:0,bottom:0}}, {rect:{width:100,height:20,top:-30,left:0,right:100,bottom:-10}}, {opacity:'0'}]) assert.equal(page([[{...user,...changes},pass]]).messages.length,0);
});
