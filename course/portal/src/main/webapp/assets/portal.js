(function (root) {
  'use strict';
  function initialize(doc, win) {
    var links = Array.prototype.slice.call(doc.querySelectorAll('.workspace-nav a[href^="#"]'));
    var sections = links.map(function (link) {
      return doc.getElementById(link.getAttribute('href').slice(1));
    });
    function activate(index) {
      links.forEach(function (link, current) {
        if (current === index) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      });
    }
    function fromScroll() {
      var active = 0;
      sections.forEach(function (section, index) {
        if (section && section.getBoundingClientRect().top <= 120) active = index;
      });
      if (doc.documentElement && doc.documentElement.scrollHeight > win.innerHeight
          && win.scrollY + win.innerHeight >= doc.documentElement.scrollHeight - 2) {
        active = links.length - 1;
      }
      activate(active);
    }
    function fromHash() {
      var index = links.findIndex(function (link) { return link.getAttribute('href') === win.location.hash; });
      if (index >= 0) activate(index);
      else fromScroll();
    }
    if (links.length) {
      links.forEach(function (link, index) { link.addEventListener('click', function () { activate(index); }); });
      win.addEventListener('hashchange', fromHash);
      var scheduled = false;
      win.addEventListener('scroll', function () {
        if (scheduled) return;
        scheduled = true;
        win.requestAnimationFrame(function () { scheduled = false; fromScroll(); });
      }, { passive: true });
      fromHash();
    }
    Array.prototype.forEach.call(doc.querySelectorAll('[data-directory-form]'), function (form) {
      var tenant = form.elements.namedItem('tenantId');
      var department = form.elements.namedItem('departmentId');
      var doctor = form.elements.namedItem('doctorId');
      var refresh = form.elements.namedItem('refreshDirectory');
      function update() {
        if (form.requestSubmit) form.requestSubmit(refresh);
        else refresh.click();
      }
      tenant.addEventListener('change', function () { department.value = ''; doctor.value = ''; update(); });
      department.addEventListener('change', function () { doctor.value = ''; update(); });
    });
  }
  if (typeof module === 'object' && module.exports) module.exports = { initialize: initialize };
  else initialize(root.document, root);
}(typeof window === 'undefined' ? this : window));
