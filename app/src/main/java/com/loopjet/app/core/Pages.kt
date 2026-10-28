package com.loopjet.app.core

/** Brand assets shared by the app and the browser pages (the only copy of the mark's vector data). */
object Branding {
    const val BLUE = "#2F6BFF"
    const val VIOLET = "#8B5CF6"
    const val CYAN = "#22D3EE"

    /**
     * The mark: a bolt that bends into a closed loop between two nodes (send ⇄ receive).
     * Same geometry as `design/loopjet-mark.svg` and the adaptive icon layers.
     */
    val logoSvg: String =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96" role="img" aria-label="LoopJet">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="$BLUE"/><stop offset=".55" stop-color="$VIOLET"/><stop offset="1" stop-color="$CYAN"/>
</linearGradient></defs>
<rect width="96" height="96" rx="26" fill="#0B1020"/>
<path d="M30 66c-9-9-9-27 3-36 8-6 20-5 27 2l6 6-8 8-6-6c-4-4-11-4-15 0-6 6-6 17 0 23 4 4 9 5 13 2"
 fill="none" stroke="url(#g)" stroke-width="7" stroke-linecap="round"/>
<path d="M52 70l14-22h-9l11-20-27 26h10l-9 16z" fill="url(#g)"/>
<circle cx="28" cy="63" r="6.5" fill="$CYAN"/>
<circle cx="68" cy="33" r="6.5" fill="$BLUE"/>
</svg>"""
}

/** Server-rendered pages: no external fonts, scripts, styles or images — they work fully offline. */
object Pages {
    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private const val CSS = """
:root{--bg:#f6f7fb;--card:#ffffff;--ink:#0d1220;--muted:#5b6478;--line:#e6e8f0;
--blue:#2f6bff;--violet:#8b5cf6;--cyan:#22d3ee;--shadow:0 18px 40px -24px rgba(18,26,64,.45)}
@media (prefers-color-scheme:dark){:root{--bg:#07090f;--card:#11151f;--ink:#f2f5ff;--muted:#9aa4bd;
--line:#1e2432;--shadow:0 18px 40px -22px rgba(0,0,0,.9)}}
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:var(--bg);color:var(--ink);
font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
-webkit-font-smoothing:antialiased}
body{min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:28px 18px 56px}
.wrap{width:100%;max-width:680px}
.hero{display:flex;gap:18px;align-items:center;margin:14px 0 22px}
.logo{width:76px;height:76px;border-radius:22px;flex:0 0 auto;box-shadow:var(--shadow)}
h1{font-size:clamp(26px,6vw,34px);line-height:1.05;letter-spacing:-.02em;margin:0}
.sub{color:var(--muted);margin:6px 0 0;font-size:15px}
.card{background:var(--card);border:1px solid var(--line);border-radius:26px;padding:20px;
box-shadow:var(--shadow);margin:16px 0}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:10px;border:none;
background:linear-gradient(100deg,var(--blue),var(--violet) 62%,var(--cyan));
color:#fff;font-weight:700;font-size:17px;padding:16px 22px;border-radius:20px;width:100%;
text-decoration:none;cursor:pointer;transition:transform .18s cubic-bezier(.2,.9,.2,1),filter .18s}
.btn:hover{filter:brightness(1.06)}.btn:active{transform:scale(.975)}
.btn.ghost{background:transparent;color:var(--ink);border:1px solid var(--line)}
.meta{display:flex;gap:14px;flex-wrap:wrap;color:var(--muted);font-size:13px;margin-top:12px}
.pill{border:1px solid var(--line);padding:6px 11px;border-radius:999px;font-size:12.5px;color:var(--muted)}
h2{font-size:15px;letter-spacing:.02em;text-transform:uppercase;color:var(--muted);margin:26px 0 10px}
ul.files{list-style:none;margin:0;padding:0}
ul.files li{display:flex;align-items:center;gap:12px;padding:13px 2px;border-bottom:1px solid var(--line)}
ul.files li:last-child{border-bottom:none}
.name{font-weight:600;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.size{color:var(--muted);font-size:12.5px;margin-top:2px}
.dl{margin-left:auto;color:var(--blue);font-weight:700;text-decoration:none;font-size:14px;
padding:8px 12px;border-radius:12px;border:1px solid var(--line)}
.dl:hover{background:rgba(47,107,255,.08)}
.drop{border:2px dashed var(--line);border-radius:22px;padding:26px;text-align:center;color:var(--muted);
transition:border-color .2s,background .2s}
.drop.over{border-color:var(--violet);background:rgba(139,92,246,.07);color:var(--ink)}
.bar{height:10px;border-radius:999px;background:var(--line);overflow:hidden;margin-top:14px;display:none}
.bar>i{display:block;height:100%;width:0;border-radius:999px;
background:linear-gradient(90deg,var(--blue),var(--violet),var(--cyan));transition:width .25s ease}
.steps{color:var(--muted);font-size:14px;line-height:1.6;margin:8px 0 0;padding-left:20px}
.steps li{margin:6px 0}
.foot{color:var(--muted);font-size:12.5px;text-align:center;margin-top:26px;line-height:1.7}
.dot{width:9px;height:9px;border-radius:50%;background:var(--cyan);display:inline-block;
animation:pulse 2.2s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:.35;transform:scale(.85)}50%{opacity:1;transform:scale(1.15)}}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
input[type=password]{width:100%;padding:14px;border-radius:16px;border:1px solid var(--line);
background:transparent;color:var(--ink);font-size:17px;letter-spacing:.4em;text-align:center}
.err{color:#e5484d;font-size:13.5px;margin-top:8px}
"""

    private fun head(title: String, extra: String = ""): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<title>${esc(title)}</title>
<link rel="icon" href="/logo.svg" type="image/svg+xml">
<style>$CSS</style>$extra</head><body><div class="wrap">"""

    private const val TAIL = """</div></body></html>"""

    fun installPage(config: WebConfig, request: WebServer.HttpRequest, packageFile: java.io.File?): String {
        val size = packageFile?.length() ?: 0L
        val token = request.query["t"] ?: ""
        val href = "/app.apk" + if (token.isNotEmpty()) "?t=$token" else ""
        return head("Install ${config.title}") + """
<div class="hero">
  <img class="logo" src="/logo.svg" alt="">
  <div><h1>${esc(config.title)}</h1><p class="sub">Install once, then send at full speed.</p></div>
</div>
<div class="card">
  <a class="btn" data-name="LoopJet-${esc(config.versionName)}-release.apk" href="$href">Download ${esc(config.title)} <span style="opacity:.75">· ${Fmt.bytes(size)}</span></a>
  <div class="meta"><span class="pill">Version ${esc(config.versionName)}</span>
  <span class="pill"><span class="dot"></span> Local only</span>
  <span class="pill">No account · No ads</span></div>
  <div class="bar" id="bar"><i id="fill"></i></div>
  <div class="meta" id="status" style="min-height:18px"></div>
</div>
<div class="card">
  <h2>After the download</h2>
  <ol class="steps">
    <li>Open the downloaded file.</li>
    <li>If Android asks, allow <b>Install unknown apps</b> for your browser.</li>
    <li>Keep this page open — the sender stays online until the transfer starts.</li>
  </ol>
</div>
<p class="foot">Served from the sender's phone over the local network.<br>
Nothing leaves your Wi-Fi. This link expires automatically.</p>
<script>
// Real byte counts from the response stream - no invented progress bar.
const bar=document.getElementById('bar'),fill=document.getElementById('fill'),st=document.getElementById('status');
const link=document.querySelector('.btn');
link.addEventListener('click',async (ev)=>{
  if(!window.fetch||!window.ReadableStream){return;}          // plain navigation is the fallback
  ev.preventDefault();
  bar.style.display='block';fill.style.width='0%';
  try{
    const res=await fetch(link.getAttribute('href'));
    if(!res.ok){throw new Error('HTTP '+res.status)}
    const total=Number(res.headers.get('content-length')||0);
    const reader=res.body.getReader(),chunks=[];let got=0;
    for(;;){
      const {done,value}=await reader.read();
      if(done)break;
      chunks.push(value);got+=value.length;
      if(total>0){fill.style.width=Math.min(100,got/total*100)+'%';
        st.textContent='Downloading '+(got/1048576).toFixed(1)+' of '+(total/1048576).toFixed(1)+' MB';}
    }
    const blob=new Blob(chunks,{type:'application/vnd.android.package-archive'});
    const a=document.createElement('a');
    a.href=URL.createObjectURL(blob);a.download=link.dataset.name||'loopjet.apk';
    document.body.appendChild(a);a.click();a.remove();
    setTimeout(()=>URL.revokeObjectURL(a.href),60000);
    fill.style.width='100%';st.textContent='Saved — open the file from your downloads.';
  }catch(e){
    st.textContent='Download failed: '+e.message+' — opening the link directly.';
    window.location.href=link.getAttribute('href');
  }
});
</script>
""" + TAIL
    }

    fun sharePage(config: WebConfig, request: WebServer.HttpRequest): String {
        val token = request.query["t"] ?: ""
        val pin = request.query["pin"] ?: ""
        // Every link has to carry both credentials, or a PIN-protected page would break its own
        // download buttons.
        val suffix = buildString {
            val parts = ArrayList<String>(2)
            if (token.isNotEmpty()) parts.add("t=$token")
            if (pin.isNotEmpty()) parts.add("pin=$pin")
            if (parts.isNotEmpty()) append("?").append(parts.joinToString("&"))
        }
        val credentials = buildString {
            if (token.isNotEmpty()) append("&t=").append(token)
            if (pin.isNotEmpty()) append("&pin=").append(pin)
        }
        val list = if (config.files.isEmpty()) {
            "<p class=\"sub\">Nothing shared yet. Add files on the phone.</p>"
        } else {
            val items = config.files.joinToString("") { f ->
                """<li><div><div class="name">${esc(f.name)}</div><div class="size">${Fmt.bytes(f.size)}</div></div>
                <a class="dl" href="/files/${f.id}$suffix">Download</a></li>"""
            }
            "<ul class=\"files\">$items</ul>"
        }
        val tooBigForZip = config.files.filter { it.file.length() > WebServer.ZIP_ENTRY_LIMIT }
        val zip = if (config.files.size > 1) {
            val note = if (tooBigForZip.isEmpty()) "" else
                "<p class=\"sub\" style=\"margin-top:8px\">${esc(tooBigForZip.joinToString { it.name })} " +
                    "is over 4 GB and is left out of the ZIP — download it on its own.</p>"
            "<a class=\"btn ghost\" style=\"margin-top:14px\" href=\"/api/zip$suffix\">Download all as ZIP</a>$note"
        } else ""
        val upload = if (config.allowUpload) """
<div class="card">
  <h2>Send to the phone</h2>
  <div class="drop" id="drop">Drop files here, or tap to choose
    <input id="picker" type="file" multiple style="display:none">
  </div>
  <div class="bar" id="ubar"><i id="ufill"></i></div>
  <div class="meta" id="ustatus"></div>
</div>
<script>
const drop=document.getElementById('drop'),picker=document.getElementById('picker');
const ubar=document.getElementById('ubar'),ufill=document.getElementById('ufill'),ust=document.getElementById('ustatus');
drop.addEventListener('click',()=>picker.click());
drop.addEventListener('dragover',e=>{e.preventDefault();drop.classList.add('over')});
drop.addEventListener('dragleave',()=>drop.classList.remove('over'));
drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('over');upload(e.dataTransfer.files)});
picker.addEventListener('change',()=>upload(picker.files));
async function upload(files){
  if(!files||!files.length)return;
  ubar.style.display='block';
  for(let i=0;i<files.length;i++){
    const f=files[i];ust.textContent='Uploading '+f.name+' ('+(i+1)+'/'+files.length+')';
    await new Promise((resolve,reject)=>{
      const xhr=new XMLHttpRequest();
      xhr.open('POST','/api/upload?name='+encodeURIComponent(f.name)+'$credentials');
      xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded/e.total*100);ufill.style.width=p+'%';}};
      xhr.onload=()=>{ufill.style.width='100%';resolve()};
      xhr.onerror=()=>{ust.textContent='Upload failed';reject()};
      xhr.send(f);
    });
  }
  ust.textContent='Sent to the phone ✓';
}
</script>
""" else ""

        return head("${config.title} · Web Share") + """
<div class="hero">
  <img class="logo" src="/logo.svg" alt="">
  <div><h1>${esc(config.title)}</h1><p class="sub">Web Share · ${config.files.size} file(s) ready</p></div>
</div>
<div class="card">
  <h2>From the phone</h2>
  $list
  $zip
</div>
$upload
<p class="foot">Local network only · session ends when the phone stops sharing.<br>
Plain HTTP on your own Wi-Fi so no certificate warnings appear.</p>
""" + TAIL
    }

    fun pinPage(path: String, hint: String, token: String? = null): String {
        val hidden = if (token.isNullOrBlank()) "" else
            """<input type="hidden" name="t" value="${esc(token)}">"""
        return head("PIN required") + """
<div class="hero"><img class="logo" src="/logo.svg" alt="">
<div><h1>PIN required</h1><p class="sub">The phone owner must approve this browser.</p></div></div>
<div class="card">
  <form method="get" action="${esc(path)}">
    $hidden
    <input type="password" name="pin" inputmode="numeric" maxlength="6" placeholder="••••" autofocus>
    <button class="btn" style="margin-top:14px" type="submit">Unlock</button>
  </form>
  <p class="sub" style="margin-top:12px">${esc(hint)}</p>
</div>
""" + TAIL
    }

    fun messagePage(title: String, body: String): String = head(title) + """
<div class="hero"><img class="logo" src="/logo.svg" alt="">
<div><h1>${esc(title)}</h1></div></div>
<div class="card"><p class="sub">${esc(body)}</p></div>
""" + TAIL
}
