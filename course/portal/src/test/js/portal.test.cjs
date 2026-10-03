const test = require('node:test');
const assert = require('node:assert/strict');
const script = '../../main/webapp/assets/portal.js';

function fixture(hash = '', anchors = ['#slot-search', '#appointment-history', '#record-billing']) {
  function element(attrs = {}) {
    return {
      attrs, events: {}, value: '', top: 0,
      getAttribute(name) { return this.attrs[name] ?? null; },
      setAttribute(name, value) { this.attrs[name] = value; },
      removeAttribute(name) { delete this.attrs[name]; },
      addEventListener(name, fn) { this.events[name] = fn; },
      getBoundingClientRect() { return { top: this.top }; }
    };
  }
  const links = anchors.map(href => element({ href }));
  const sections = links.map((link, index) => { const section = element(); section.top = 300 + index * 500; return section; });
  const controls = { tenantId: element(), departmentId: element(), doctorId: element(), refreshDirectory: element() };
  controls.tenantId.value = 'hospital-a'; controls.departmentId.value = 'dept-a'; controls.doctorId.value = 'doctor-a';
  const submissions = [];
  const form = { elements: { namedItem: name => controls[name] }, requestSubmit: button => submissions.push(button) };
  const doc = {
    querySelectorAll: selector => selector === '.workspace-nav a[href^="#"]' ? links : [form],
    getElementById: id => sections[links.findIndex(link => link.attrs.href === '#' + id)] ?? null
  };
  const win = { location: { hash }, events: {}, addEventListener(name, fn) { this.events[name] = fn; }, requestAnimationFrame(fn) { fn(); } };
  return { links, sections, controls, submissions, doc, win };
}

function initialize(f) {
  let module;
  assert.doesNotThrow(() => { module = require(script); }, 'The workspace enhancement script must be present');
  module.initialize(f.doc, f.win);
}

test('direct record hash, click and manual scroll update exactly one accessible current link', () => {
  const f = fixture('#record-billing'); initialize(f);
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, undefined, 'location']);
  f.links[1].events.click();
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, 'location', undefined]);
  f.sections[0].top = -500; f.sections[1].top = -100; f.sections[2].top = 300;
  f.win.events.scroll();
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, 'location', undefined]);
  f.win.location.hash = '#unknown'; f.win.events.hashchange();
  assert.equal(f.links[1].attrs['aria-current'], 'location', 'Unknown anchors must not produce a dead active link');
});

test('changing hospital clears both dependent identities and submits the read-only refresh button', () => {
  const f = fixture(); initialize(f); f.controls.tenantId.events.change();
  assert.equal(f.controls.departmentId.value, ''); assert.equal(f.controls.doctorId.value, '');
  assert.deepEqual(f.submissions, [f.controls.refreshDirectory]);
});

test('scrolling to the bottom activates a short final section that cannot reach the upper threshold', () => {
  const f = fixture(); initialize(f);
  f.doc.documentElement = { scrollHeight: 2000 };
  f.win.innerHeight = 900; f.win.scrollY = 1100;
  f.sections[0].top = -1000; f.sections[1].top = -300; f.sections[2].top = 500;
  f.win.events.scroll();
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, undefined, 'location']);
});

test('changing department preserves hospital and resets only the doctor before refreshing', () => {
  const f = fixture(); initialize(f); f.controls.departmentId.events.change();
  assert.equal(f.controls.tenantId.value, 'hospital-a'); assert.equal(f.controls.departmentId.value, 'dept-a');
  assert.equal(f.controls.doctorId.value, ''); assert.deepEqual(f.submissions, [f.controls.refreshDirectory]);
});

test('platform statistics hash and final architecture section keep one current link', () => {
  const f = fixture('#platform-statistics', ['#tenant-management', '#platform-statistics', '#daily-trend', '#architecture']);
  initialize(f);
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, 'location', undefined, undefined]);
  f.doc.documentElement = { scrollHeight: 3000 }; f.win.innerHeight = 900; f.win.scrollY = 2100;
  f.win.events.scroll();
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, undefined, undefined, 'location']);
});

test('hospital trend hash does not remove access to existing management sections', () => {
  const f = fixture('#daily-trend', ['#departments', '#doctors', '#schedule', '#orders', '#hospital-statistics', '#daily-trend']);
  initialize(f);
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, undefined, undefined, undefined, undefined, 'location']);
  f.links[3].events.click();
  assert.deepEqual(f.links.map(link => link.attrs['aria-current']), [undefined, undefined, undefined, 'location', undefined, undefined]);
});
