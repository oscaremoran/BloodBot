"""Brain MRI: a page showing the PPO brain's weights at each saved snapshot (runs/ppo/brains/*.bin).

- Eyes: how strongly the first layer connects to each map layer, minimap vs close-up.
- Scan: the 256 x 256 middle layer (d2) as a picture, blue = negative, red = positive.
- Change: how much each d2 connection moved since the first snapshot.

Usage: nice -n 19 .venv/bin/python -m bloodbot.brain_mri  ->  runs/brain_mri.html
"""
import base64
import json
import re
import struct
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
BRAINS = ROOT / "runs" / "ppo" / "brains"
OUT = ROOT / "runs" / "brain_mri.html"


def layer_names():
    src = (ROOT / "common/src/main/java/bloodbot/Vision.java").read_text()
    block = re.search(r"LAYERS = \{(.*?)\};", src, re.S).group(1)
    return re.findall(r'"([^"]+)"', block)


def load(path: Path):
    raw = path.read_bytes()
    (n,) = struct.unpack(">i", raw[:4])
    C, G, k1, k2, k3, S, H, O = struct.unpack(f">{n}i", raw[4:4 + 4 * n])
    p = np.frombuffer(raw[4 + 4 * n:], ">f4").astype(np.float32)
    flat = k3 * (G // 8) ** 2
    shapes = [(k1, C, 3, 3), (k1,), (k2, k1, 3, 3), (k2,), (k3, k2, 3, 3), (k3,),
              (H, flat + S), (H,), (H, H), (H,), (O, H), (O,)]
    out, i = [], 0
    for s in shapes:
        size = int(np.prod(s))
        out.append(p[i:i + size].reshape(s))
        i += size
    assert i == p.size, "unexpected brain size"
    return {"c1": out[0], "d2": out[8]}


def q8(m: np.ndarray, scale: float) -> str:
    """Signed weights as int8 (value = byte / 127 * scale), base64."""
    b = np.clip(np.round(m / scale * 127), -127, 127).astype(np.int8)
    return base64.b64encode(b.tobytes()).decode()


def main():
    names = layer_names()
    files = sorted(BRAINS.glob("update_*.bin")) + [BRAINS / "latest.bin"]
    snaps = [(f.stem.replace("update_", "update ").lstrip("0") if f.stem != "latest" else "now", load(f)) for f in files]
    snaps = [(re.sub(r"update 0+", "update ", lab), b) for lab, b in snaps]
    first = snaps[0][1]["d2"]
    scale = float(max(np.abs(b["d2"]).max() for _, b in snaps))
    dscale = float(max(np.abs(b["d2"] - first).max() for _, b in snaps)) or 1.0
    data = []
    for lab, b in snaps:
        eyes = np.abs(b["c1"]).sum(axis=(0, 2, 3))  # per input layer
        C = len(names)
        data.append({"label": lab,
                     "mini": [round(float(v), 3) for v in eyes[:C]],
                     "close": [round(float(v), 3) for v in eyes[C:2 * C]],
                     "d2": q8(b["d2"], scale), "delta": q8(np.abs(b["d2"] - first), dscale)})
    page = TEMPLATE.replace("__DATA__", json.dumps({"names": names, "snaps": data, "scale": scale,
                                                     "dscale": dscale, "n": int(first.shape[0])}))
    OUT.write_text(page)
    print(f"{OUT} ({len(data)} snapshots)")


TEMPLATE = r"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>BloodBot brain MRI</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{--surface:#fcfcfb;--ink:#0b0b0b;--ink2:#52514e;--muted:#8a8984;--grid:#e6e5e0;--s1:#2a78d6;--s2:#eb6834;--mid:#f0efec}
@media (prefers-color-scheme: dark){:root{--surface:#1a1a19;--ink:#f0efec;--ink2:#c3c2b7;--muted:#8f8e87;--grid:#2e2e2c;--s1:#3987e5;--s2:#d95926;--mid:#383835}}
body{background:var(--surface);color:var(--ink);font:14px/1.45 system-ui,-apple-system,sans-serif;margin:0;padding:24px;max-width:1100px}
h1{font-size:22px;margin:0 0 4px} h2{font-size:16px;margin:28px 0 4px} p{color:var(--ink2);margin:4px 0 12px}
.controls{display:flex;gap:12px;align-items:center;margin:16px 0;flex-wrap:wrap}
.controls button{background:none;border:1px solid var(--grid);color:var(--ink);border-radius:6px;padding:4px 10px;cursor:pointer;font:inherit}
.controls button[aria-pressed=true]{border-color:var(--ink);font-weight:600}
.eyes{display:grid;grid-template-columns:170px 1fr 1fr;gap:2px 14px;align-items:center}
.eyes .h{font-size:12px;color:var(--ink2);font-weight:600;padding-bottom:4px;display:flex;align-items:center;gap:6px}
.eyes .n{font-size:12px;color:var(--ink2);text-align:right;white-space:nowrap}
.track{position:relative;height:12px}
.bar{position:absolute;left:0;top:1px;height:10px;border-radius:0 4px 4px 0;transition:width .3s}
.val{position:absolute;top:-2px;font-size:11px;color:var(--muted);transition:left .3s;white-space:nowrap}
.sw{width:10px;height:10px;border-radius:2px;display:inline-block}
.scans{display:flex;gap:24px;flex-wrap:wrap}
.scans figure{margin:0} figcaption{font-size:12px;color:var(--ink2);margin-top:6px;max-width:384px}
canvas{image-rendering:pixelated;width:384px;height:384px;border-radius:4px;display:block}
.legend{display:flex;align-items:center;gap:6px;font-size:11px;color:var(--muted);margin-top:4px}
.legend i{display:block;width:140px;height:8px;border-radius:2px}
#tip{position:fixed;pointer-events:none;background:var(--surface);color:var(--ink);border:1px solid var(--grid);border-radius:6px;padding:6px 8px;font-size:12px;display:none;box-shadow:0 2px 8px #0003}
details{margin-top:24px} table{border-collapse:collapse;font-size:12px} td,th{padding:2px 10px;text-align:right;border-bottom:1px solid var(--grid)} th:first-child,td:first-child{text-align:left}
</style></head><body>
<h1>BloodBot brain MRI</h1>
<p>The bot's brain at each saved snapshot. Pick one to see how it changed.</p>
<div class="controls" id="ctl"></div>

<h2>What its eyes are wired to</h2>
<p>How strongly the first layer connects to each kind of thing on the map: the whole-map minimap vs the close-up around its ship. The close-up started switched off (all zeros).</p>
<div class="eyes" id="eyes"></div>

<h2>Brain scan</h2>
<p>The middle layer: 256 neurons, each connected to all 256 in the layer before. One pixel per connection.</p>
<div class="scans">
 <figure><canvas id="scan" width="256" height="256"></canvas>
  <div class="legend">negative <i id="lg1"></i> positive</div>
  <figcaption>Blue pushes a neuron down, red pushes it up, gray does little.</figcaption></figure>
 <figure><canvas id="delta" width="256" height="256"></canvas>
  <div class="legend">no change <i id="lg2"></i> big change</div>
  <figcaption id="dcap">How far each connection moved since the first snapshot.</figcaption></figure>
</div>

<details><summary>Table: eye wiring per map layer</summary><div id="tbl"></div></details>
<div id="tip"></div>
<script>
const D = __DATA__;
const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const blue = ['#cde2fb','#9ec5f4','#6da7ec','#3987e5','#256abf','#184f95','#0d366b'];
const red = ['#fbd3d2','#f4a9a8','#ec7e7d','#e34948','#c43534','#9c2726','#741b1a'];
const hex = h => [1,3,5].map(i => parseInt(h.slice(i,i+2),16));
function ramp(stops, t){ t=Math.max(0,Math.min(1,t))*(stops.length-1); const i=Math.min(stops.length-2,Math.floor(t)), f=t-i;
  const a=hex(stops[i]), b=hex(stops[i+1]); return a.map((v,k)=>Math.round(v+(b[k]-v)*f)); }
function decode(b64){ const s=atob(b64), a=new Int8Array(s.length); for(let i=0;i<s.length;i++) a[i]=s.charCodeAt(i)<<24>>24; return a; }
const mats = D.snaps.map(s => ({d2: decode(s.d2), delta: decode(s.delta)}));
let cur = D.snaps.length - 1;

const ctl = document.getElementById('ctl');
D.snaps.forEach((s,i) => { const b=document.createElement('button'); b.textContent=s.label; b.onclick=()=>{cur=i;draw()}; ctl.appendChild(b); });

const maxEye = Math.max(...D.snaps.flatMap(s => s.mini.concat(s.close)));
const eyes = document.getElementById('eyes');
eyes.innerHTML = `<div></div><div class="h"><span class="sw" style="background:var(--s1)"></span>Minimap</div><div class="h"><span class="sw" style="background:var(--s2)"></span>Close-up</div>`;
const rows = D.names.map((n,i) => {
  const name=document.createElement('div'); name.className='n'; name.textContent=n; eyes.appendChild(name);
  const mk = col => { const t=document.createElement('div'); t.className='track'; const b=document.createElement('div'); b.className='bar'; b.style.background=`var(${col})`;
    const v=document.createElement('span'); v.className='val'; t.append(b,v); eyes.appendChild(t); return {b,v,t}; };
  return {mini: mk('--s1'), close: mk('--s2')};
});

function paint(canvas, arr, fn){
  const ctx=canvas.getContext('2d'), N=D.n, img=ctx.createImageData(N,N);
  for(let i=0;i<N*N;i++){ const c=fn(arr[i]/127); img.data.set([c[0],c[1],c[2],255], i*4); }
  ctx.putImageData(img,0,0);
}
const mid = () => hex(css('--mid'));
const diverge = v => { if(Math.abs(v)<0.02) return mid(); const m=mid(), c=ramp(v<0?blue:red, Math.sqrt(Math.abs(v))); const t=Math.min(1,Math.abs(v)*6); return m.map((x,k)=>Math.round(x+(c[k]-x)*t)); };
const seq = v => v<0.02 ? mid() : ramp(blue, Math.sqrt(v));
document.getElementById('lg1').style.background = `linear-gradient(90deg, ${blue[5]}, ${css('--mid')}, ${red[5]})`;
document.getElementById('lg2').style.background = `linear-gradient(90deg, ${css('--mid')}, ${blue[2]}, ${blue[6]})`;

function draw(){
  [...ctl.children].forEach((b,i)=>b.setAttribute('aria-pressed', i===cur));
  const s=D.snaps[cur];
  rows.forEach((r,i)=>{ for(const k of ['mini','close']){ const v=s[k][i], w=v/maxEye*100;
    r[k].b.style.width=w+'%'; r[k].v.style.left=`calc(${w}% + 4px)`; r[k].v.textContent=v.toFixed(1);
    r[k].t.dataset.tip=`<b>${D.names[i]}</b> · ${k==='mini'?'minimap':'close-up'}<br>${s.label}: ${v.toFixed(2)}`; } });
  paint(document.getElementById('scan'), mats[cur].d2, diverge);
  paint(document.getElementById('delta'), mats[cur].delta, seq);
  document.getElementById('dcap').textContent = `How far each connection moved from ${D.snaps[0].label} to ${s.label}.`;
  document.getElementById('tbl').innerHTML = '<table><tr><th>map layer</th><th>minimap</th><th>close-up</th></tr>' +
    D.names.map((n,i)=>`<tr><td>${n}</td><td>${s.mini[i].toFixed(2)}</td><td>${s.close[i].toFixed(2)}</td></tr>`).join('') + '</table>';
}
const tip=document.getElementById('tip');
function show(e,html){ tip.innerHTML=html; tip.style.display='block'; tip.style.left=(e.clientX+12)+'px'; tip.style.top=(e.clientY+12)+'px'; }
eyes.addEventListener('mousemove', e=>{ const t=e.target.closest('.track'); t?show(e,t.dataset.tip):tip.style.display='none'; });
eyes.addEventListener('mouseleave', ()=>tip.style.display='none');
for(const [id,key,sc,lab] of [['scan','d2',D.scale,'weight'],['delta','delta',D.dscale,'moved']]){
  const c=document.getElementById(id);
  c.addEventListener('mousemove', e=>{ const r=c.getBoundingClientRect(), x=Math.floor((e.clientX-r.left)/r.width*D.n), y=Math.floor((e.clientY-r.top)/r.height*D.n);
    if(x<0||y<0||x>=D.n||y>=D.n) return; const v=mats[cur][key][y*D.n+x]/127*sc;
    show(e, `neuron ${y} ← neuron ${x}<br>${lab}: ${v.toFixed(3)}`); });
  c.addEventListener('mouseleave', ()=>tip.style.display='none');
}
draw();
</script></body></html>
"""

if __name__ == "__main__":
    main()
