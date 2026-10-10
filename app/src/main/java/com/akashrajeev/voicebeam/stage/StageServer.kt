package com.akashrajeev.voicebeam.stage

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tiny local web server so a laptop (through Office Kit or the same Wi-Fi)
 * can show big "stage captions". Nothing leaves the local network.
 */
class StageServer(port: Int, private val snapshot: () -> JSONObject) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/state.json" -> newFixedLengthResponse(Response.Status.OK, "application/json", snapshot().toString()).apply {
            addHeader("Cache-Control", "no-store")
        }
        else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
    }

    companion object {
        const val PORT = 8765

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } catch (_: Throwable) { null }

        fun state(lines: List<Pair<String, Boolean>>, partial: String, rec: Boolean, recClock: String, locked: Boolean, quiet: Int): JSONObject =
            JSONObject().apply {
                put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("text", it.first).put("target", it.second)) } })
                put("partial", partial)
                put("rec", rec); put("recClock", recClock)
                put("locked", locked); put("quiet", quiet)
            }

        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VoiceBeam · Stage</title>
<style>
*{box-sizing:border-box;margin:0}body{background:#050607;color:#F4F6F8;font-family:Inter,Roboto,'Segoe UI',sans-serif;height:100vh;display:flex;flex-direction:column;padding:28px 6vw}
.top{display:flex;justify-content:space-between;align-items:center}.brand{font-weight:700;font-size:20px;display:flex;gap:10px;align-items:center}
.chip{font-size:13px;font-weight:600;padding:6px 12px;border-radius:99px;background:#171A1F;margin-left:8px}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px}
.cap{flex:1;display:flex;flex-direction:column;justify-content:flex-end;gap:18px;padding-bottom:30px}
.old{font-size:3.2vw;line-height:1.35;color:#5E656E}.other{font-style:italic}.now{font-size:5.4vw;line-height:1.2;font-weight:600;letter-spacing:-.02em}
.cur{display:inline-block;width:.12em;height:.9em;background:#3DDBB0;margin-left:.1em;vertical-align:-.1em;animation:b 1s steps(2) infinite}@keyframes b{50%{opacity:0}}
.foot{display:flex;justify-content:space-between;color:#6B727B;font-size:14px}.acc{color:#3DDBB0;font-weight:600}
</style></head><body>
<div class="top"><div class="brand"><svg width="26" height="26" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="none" stroke="#3DDBB0" stroke-width="2.5"/><circle cx="15" cy="15" r="4.5" fill="#3DDBB0"/></svg>VoiceBeam · Stage</div>
<div><span class="chip"><span class="dot" style="background:#3DDBB0"></span>Phone connected</span><span class="chip" id="rec" style="display:none"><span class="dot" style="background:#FF4D4F"></span><span id="rc">REC</span></span></div></div>
<div class="cap" id="cap"></div>
<div class="foot"><span id="st"><span class="acc">Waiting</span></span><span>On-device captions from the phone</span></div>
<script>
async function tick(){try{const r=await fetch('/state.json',{cache:'no-store'});const s=await r.json();
const cap=document.getElementById('cap');let h='';const ls=s.lines.slice(-2);
for(const l of ls){h+='<div class="old'+(l.target?'':' other')+'">'+esc((l.target?'':'Others: ')+l.text)+'</div>'}
h+='<div class="now">'+esc(s.partial||'')+'<span class="cur"></span></div>';cap.innerHTML=h;
document.getElementById('rec').style.display=s.rec?'inline-block':'none';document.getElementById('rc').textContent='REC '+s.recClock;
document.getElementById('st').innerHTML=s.locked?'<span class="acc">Speaker 1</span> · locked · others quieted '+s.quiet+'%':'<span class="acc">Listening</span> · tap a face on the phone to lock';
}catch(e){document.getElementById('st').textContent='Reconnecting to phone...'}}
function esc(t){return t.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
setInterval(tick,250);tick();
</script></body></html>
""".trimIndent()
    }
}
