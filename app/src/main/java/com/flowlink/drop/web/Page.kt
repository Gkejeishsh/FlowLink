package com.flowlink.drop.web

/**
 * The page the guest sees. One file, no external requests of any kind — it has to work on a network
 * whose entire purpose is having no internet.
 *
 * Two rules that shaped it:
 *  * `http://192.168.x.x` is not a secure context, so there is no clipboard API, no
 *    `crypto.randomUUID`, no Wake Lock and no service worker. None are used.
 *  * It must work with JavaScript disabled, so a plain multipart form is the fallback.
 */
object Page {

    fun render(phoneName: String, maxBytes: Long, version: String): String {
        val name = escapeHtml(phoneName)
        return TEMPLATE
            .replace("__PHONE__", name)
            .replace("__MAX__", hsize(maxBytes))
            .replace("__VERSION__", escapeHtml(version))
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun hsize(bytes: Long): String {
        if (bytes <= 0) return "4 GB"
        val gb = bytes.toDouble() / (1024.0 * 1024 * 1024)
        return if (gb >= 1) String.format(java.util.Locale.US, "%.0f GB", gb) else
            String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    }

    private val TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b0f17">
<title>Send to __PHONE__</title>
<style>
:root{
  --bg:#0b0f17; --card:#131926; --card-2:#1a2130; --line:#232a3a;
  --text:#f2f5ff; --dim:#aeb7cc; --accent:#255ce8; --accent-2:#8b5cf6;
  --ok:#4ade80; --bad:#ff6b6b; --r:26px;
}
*{box-sizing:border-box}
html,body{margin:0;min-height:100%}
body{
  background:
    radial-gradient(1200px 600px at 12% -10%, rgba(37,92,232,.28), transparent 60%),
    radial-gradient(900px 500px at 100% 0%, rgba(139,92,246,.22), transparent 55%),
    var(--bg);
  color:var(--text);
  font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif;
  -webkit-font-smoothing:antialiased;
  padding:calc(24px + env(safe-area-inset-top)) 16px calc(32px + env(safe-area-inset-bottom));
  display:flex; justify-content:center;
}
main{width:100%;max-width:620px}
header{display:flex;align-items:center;gap:12px;margin-bottom:22px}
.mark{width:42px;height:42px;border-radius:14px;flex:0 0 auto;
  background:linear-gradient(135deg,var(--accent),var(--accent-2));
  display:grid;place-items:center;box-shadow:0 8px 30px rgba(37,92,232,.35)}
.mark svg{width:22px;height:22px}
h1{font-size:22px;line-height:1.2;margin:0;letter-spacing:-.02em}
.sub{color:var(--dim);font-size:13.5px;margin:2px 0 0}
.card{background:var(--card);border-radius:var(--r);padding:18px;border:1px solid rgba(255,255,255,.04)}
#drop{
  position:relative;border:2px dashed rgba(255,255,255,.16);border-radius:22px;
  padding:38px 20px;text-align:center;cursor:pointer;
  transition:transform .25s cubic-bezier(.2,.9,.25,1),border-color .2s,background .2s;
  background:linear-gradient(180deg,rgba(255,255,255,.035),rgba(255,255,255,.015));
  outline:none;
}
#drop:hover,#drop:focus-visible{border-color:rgba(122,162,255,.55);background:rgba(37,92,232,.10)}
#drop.drag{transform:scale(1.02);border-color:var(--accent);background:rgba(37,92,232,.16)}
#drop.busy{pointer-events:none;opacity:.75}
.arrow{width:46px;height:46px;margin:0 auto 12px;border-radius:50%;
  background:linear-gradient(135deg,var(--accent),var(--accent-2));display:grid;place-items:center;
  animation:breathe 3.2s ease-in-out infinite}
.arrow svg{width:22px;height:22px}
@keyframes breathe{0%,100%{transform:translateY(0) scale(1)}50%{transform:translateY(-3px) scale(1.04)}}
#drop strong{font-size:17px;display:block;margin-bottom:4px}
#drop span{color:var(--dim);font-size:14px}
ul.list{list-style:none;margin:16px 0 0;padding:0;display:flex;flex-direction:column;gap:10px}
li.item{background:var(--card-2);border-radius:16px;padding:12px 14px;
  display:grid;grid-template-columns:1fr auto;gap:2px 10px;align-items:center;
  animation:pop .32s cubic-bezier(.2,1.12,.3,1) both}
@keyframes pop{from{opacity:0;transform:translateY(-8px) scale(.985)}to{opacity:1;transform:none}}
.nm{font-size:14.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.meta{grid-column:1/-1;color:var(--dim);font-size:12.5px;font-variant-numeric:tabular-nums}
.bar{grid-column:1/-1;height:6px;border-radius:99px;background:rgba(255,255,255,.09);overflow:hidden;margin-top:8px}
.bar i{display:block;height:100%;width:0;border-radius:99px;
  background:linear-gradient(90deg,var(--accent),var(--accent-2));
  transition:width .35s cubic-bezier(.2,.9,.25,1)}
.state{font-size:12.5px;font-weight:600;color:var(--dim);white-space:nowrap}
li.done .state{color:var(--ok)}
li.fail .state{color:var(--bad)}
li.fail .bar i{background:var(--bad)}
.retry{background:none;border:1px solid var(--line);color:var(--text);border-radius:12px;
  padding:6px 10px;font:inherit;font-size:12.5px;cursor:pointer}
.totals{display:flex;justify-content:space-between;color:var(--dim);font-size:13px;margin-top:14px;
  font-variant-numeric:tabular-nums}
.note{margin-top:16px;color:var(--dim);font-size:13px}
.note b{color:var(--text);font-weight:600}
.banner{display:none;margin-top:14px;padding:12px 14px;border-radius:16px;
  background:rgba(255,107,107,.12);border:1px solid rgba(255,107,107,.35);color:#ffd0d0;font-size:13.5px}
.banner.show{display:block;animation:pop .3s both}
footer{margin-top:18px;text-align:center;color:var(--dim);font-size:12px}
.ghost{display:inline-block;margin-top:10px;color:var(--dim);text-decoration:none;
  border-bottom:1px dotted var(--line);cursor:pointer}
.nojs p{color:var(--dim);font-size:14px}
.nojs input,.nojs button{display:block;width:100%;margin-top:10px;font:inherit;padding:12px;border-radius:14px;
  border:1px solid var(--line);background:var(--card-2);color:var(--text)}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
</style>
</head>
<body>
<main>
  <header>
    <div class="mark" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round">
        <path d="M12 4v11"/><path d="M7.5 10.5L12 15l4.5-4.5"/><path d="M5 20h14"/>
      </svg>
    </div>
    <div>
      <h1>Send to __PHONE__</h1>
      <p class="sub">Straight over Wi-Fi. Nothing leaves this network.</p>
    </div>
  </header>

  <section class="card">
    <div id="drop" role="button" tabindex="0" aria-label="Choose files to send">
      <div class="arrow" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round">
          <path d="M12 19V6"/><path d="M6.5 11.5L12 6l5.5 5.5"/>
        </svg>
      </div>
      <strong>Drop files here</strong>
      <span>or tap to choose from this device</span>
    </div>

    <input id="picker" type="file" multiple style="display:none">

    <noscript>
      <style>#drop{display:none}</style>
      <form action="/upload" method="post" enctype="multipart/form-data" class="nojs">
        <p>JavaScript is off — this form still works.</p>
        <input type="file" name="file" multiple required>
        <button type="submit">Send</button>
      </form>
    </noscript>

    <ul class="list" id="list" aria-live="polite"></ul>

    <div class="totals" id="totals" hidden>
      <span id="count">0 files</span>
      <span id="speed"></span>
    </div>

    <div class="banner" id="banner">
      Lost the phone. Check that this device is still joined to its Wi-Fi, then try again.
    </div>

    <p class="note" id="hint">
      <b>No internet?</b> Correct — this network exists only to reach the phone. Large files are
      limited to __MAX__ each.
    </p>
  </section>

  <footer>
    FlowLink Drop __VERSION__ · files go one way, to the phone
    <br><a class="ghost" id="again" href="/">Send more</a>
  </footer>
</main>

<script>
(function () {
  var drop = document.getElementById('drop');
  var picker = document.getElementById('picker');
  var list = document.getElementById('list');
  var totals = document.getElementById('totals');
  var countEl = document.getElementById('count');
  var speedEl = document.getElementById('speed');
  var banner = document.getElementById('banner');
  var sent = 0, totalBytes = 0, startedAt = 0, queue = 0;


  function human(n) {
    if (n < 1024) return n + ' B';
    var units = ['KB', 'MB', 'GB', 'TB'], i = -1, v = n;
    do { v = v / 1024; i++; } while (v >= 1024 && i < units.length - 1);
    return (v >= 100 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
  }

  function rowFor(file) {
    var li = document.createElement('li');
    li.className = 'item';
    var name = document.createElement('div');
    name.className = 'nm';
    name.textContent = file.name;
    var state = document.createElement('div');
    state.className = 'state';
    state.textContent = 'queued';
    var meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = human(file.size);
    var bar = document.createElement('div');
    bar.className = 'bar';
    var fill = document.createElement('i');
    bar.appendChild(fill);
    li.appendChild(name); li.appendChild(state); li.appendChild(meta); li.appendChild(bar);
    list.appendChild(li);
    return { root: li, state: state, meta: meta, fill: fill, file: file, sent: 0 };
  }

  function updateTotals() {
    if (!sent) return;
    totals.hidden = false;
    countEl.textContent = sent + (sent === 1 ? ' file' : ' files') + ' · ' + human(totalBytes);
    var seconds = (Date.now() - startedAt) / 1000;
    speedEl.textContent = seconds > 0.4 ? human(totalBytes / seconds) + '/s' : '';
  }

  function upload(row) {
    var xhr = new XMLHttpRequest();
    var url = '/api/upload?name=' + encodeURIComponent(row.file.name);
    row.state.textContent = '0%';
    row.root.className = 'item';
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = function (event) {
      if (!event.lengthComputable) return;
      var pct = Math.round((event.loaded / event.total) * 100);
      row.fill.style.width = pct + '%';
      row.state.textContent = pct + '%';
      row.meta.textContent = human(event.loaded) + ' of ' + human(event.total);
    };
    xhr.onload = function () {
      if (xhr.status >= 200 && xhr.status < 300) {
        row.fill.style.width = '100%';
        row.root.className = 'item done';
        row.state.textContent = 'sent';
        row.meta.textContent = human(row.file.size);
        sent++; totalBytes += row.file.size; updateTotals();
      } else {
        fail(row, xhr.status === 413 ? 'too large' : xhr.status === 507 ? 'phone full' : 'failed');
      }
      settle();
    };
    xhr.onerror = function () {
      banner.className = 'banner show';
      fail(row, 'lost connection');
      settle();
    };
    xhr.onabort = function () { fail(row, 'cancelled'); settle(); };
    xhr.send(row.file);
  }

  function fail(row, reason) {
    row.root.className = 'item fail';
    row.state.textContent = reason;
    if (!row.root.querySelector('.retry')) {
      var retry = document.createElement('button');
      retry.className = 'retry';
      retry.type = 'button';
      retry.textContent = 'Try again';
      retry.onclick = function () {
        retry.remove();
        row.fill.style.width = '0%';
        queue++;
        upload(row);
      };
      row.root.appendChild(retry);
    }
  }

  function settle() {
    queue = Math.max(0, queue - 1);
    if (queue === 0) drop.classList.remove('busy');
    updateTotals();
  }

  function send(files) {
    if (!files || !files.length) return;
    if (!startedAt) startedAt = Date.now();
    banner.className = 'banner';
    drop.classList.add('busy');
    for (var i = 0; i < files.length; i++) {
      var row = rowFor(files[i]);
      queue++;
      upload(row);
    }
  }

  drop.addEventListener('click', function () { picker.click(); });
  drop.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); picker.click(); }
  });
  picker.addEventListener('change', function () { send(picker.files); picker.value = ''; });

  ['dragenter', 'dragover'].forEach(function (name) {
    drop.addEventListener(name, function (e) { e.preventDefault(); drop.classList.add('drag'); });
  });
  ['dragleave', 'drop'].forEach(function (name) {
    drop.addEventListener(name, function (e) { e.preventDefault(); drop.classList.remove('drag'); });
  });
  drop.addEventListener('drop', function (e) {
    if (e.dataTransfer && e.dataTransfer.files) send(e.dataTransfer.files);
  });
  document.addEventListener('dragover', function (e) { e.preventDefault(); });
  document.addEventListener('drop', function (e) { e.preventDefault(); });

  document.getElementById('again').addEventListener('click', function (e) {
    e.preventDefault();
    list.innerHTML = ''; sent = 0; totalBytes = 0; startedAt = 0;
    totals.hidden = true; banner.className = 'banner';
  });
})();
</script>
</body>
</html>
    """
}
