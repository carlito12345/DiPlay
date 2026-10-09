#!/usr/bin/env python3
"""Generate the static GitHub Pages editions, one per language in content.json; no runtime dependencies."""
from pathlib import Path
import json
from html import escape as e
ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
data = json.loads((SITE / 'content.json').read_text())
BASE = 'https://shihabal3amri.github.io/DiPlay/'
REPO = 'https://github.com/shihabal3amri/DiPlay'
VERSION = '0.2.16'
RELEASE = REPO + f'/releases/tag/v{VERSION}'
DOWNLOAD = REPO + f'/releases/download/v{VERSION}/DiPlay-{VERSION}.apk'
for lang, d in data.items():
    folder = SITE if lang == 'en' else SITE / lang
    folder.mkdir(exist_ok=True)
    prefix = './' if lang == 'en' else '../'
    url = BASE + ('' if lang == 'en' else lang + '/')
    nav = ''.join(f'<a href="{prefix}{"" if code == "en" else code + "/"}" lang="{code}" hreflang="{code}" dir="auto"'+(' aria-current="page"' if code == lang else '')+f'>{e(v["name"])}</a>' for code,v in data.items())
    alternates = ''.join(f'<link rel="alternate" hreflang="{code}" href="{BASE}{"" if code == "en" else code + "/"}">' for code in data)
    pics = ''.join(f'<figure><a href="{prefix}assets/{pic}.png"><img src="{prefix}assets/{pic}.png" width="1920" height="1080" loading="lazy" alt="{e(cap)}"></a><figcaption>{e(cap)}</figcaption></figure>' for pic,cap in zip(['home','settings'],d['captions']))
    (folder/'index.html').write_text(f'''<!doctype html>
<html lang="{lang}" dir="{d['dir']}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiPlay · {e(d['download'])}</title><meta name="description" content="{e(d['intro'])}"><meta name="theme-color" content="#0c121c">
<link rel="icon" href="{prefix}assets/icon.png"><link rel="stylesheet" href="{prefix}assets/site.css"><link rel="canonical" href="{url}">{alternates}
<meta property="og:title" content="DiPlay — CarPlay for compatible Android head units"><meta property="og:description" content="{e(d['promise'])}"><meta property="og:image" content="{BASE}assets/home.png"><meta property="og:url" content="{url}"><meta property="og:type" content="website">
</head><body><main>
<header><a class="brand" href="{prefix}"><img src="{prefix}assets/icon.png" width="56" height="56" alt=""><span><strong>DiPlay</strong><small>{e(d['tag'])}</small></span></a><nav class="languages" aria-label="Language">{nav}</nav></header>
<section class="hero"><span class="badge">{e(d['badge'])} · <bdi>{VERSION}</bdi></span><h1>{e(d['title']).replace(chr(10),'<br>')}</h1><p class="intro">{e(d['intro'])}</p><div class="actions"><a class="button" href="{DOWNLOAD}">{e(d['download'])} <span aria-hidden="true">↓</span></a><a class="button secondary" href="#install">{e(d['install'])}</a></div><p class="promise">{e(d['promise'])}</p><p class="note">{e(d['requires'])}</p><p class="note support-scope"><strong>{e(d['supportScope'])}</strong></p></section>
<section class="gallery"><h2>{e(d['gallery'])}</h2><div class="screens">{pics}</div></section>
<div class="grid"><section class="card" id="install"><span class="eyebrow">01</span><h2>{e(d['setup'])}</h2><ol>{''.join('<li>'+e(x)+'</li>' for x in d['steps'])}</ol><p class="note">{e(d['bssid'])}</p><a href="{REPO}/blob/main/docs/INSTALL.md">{e(d['adb'])} ↗</a></section>
<section class="card"><span class="eyebrow">02</span><h2>{e(d['whats'])}</h2><ul>{''.join('<li>'+e(x)+'</li>' for x in d['features'])}</ul><a href="{RELEASE}">{e(d['notes'])} ↗</a><h3>{e(d['compat'])}</h3><p>{e(d['compatText'])}</p></section></div>
<section class="card updates"><div><h2>{e(d['follow'])}</h2><p>{e(d['followText'])}</p></div><a class="button secondary" href="https://t.me/byd_localized">{e(d['telegram'])} ↗</a></section>
<section class="signing"><h2>{e(d['update'])}</h2><p>{e(d['updateText'])}</p></section>
<section class="card"><h2>{e(d['diagnosticsTitle'])}</h2><p>{e(d['diagnosticsText'])}</p><a href="{REPO}/issues">{e(d['feedback'])} ↗</a> · <a href="{REPO}/issues/new/choose">{e(d['newIssue'])} ↗</a></section>
<footer><nav><a href="{REPO}/blob/main/docs/PRIVACY.md">{e(d["privacy"])}</a><a href="{REPO}">{e(d['source'])}</a><a href="{RELEASE}">{e(d['notes'])}</a><a href="{REPO}/issues">{e(d['feedback'])}</a></nav><p>{e(d['footer'])}</p></footer>
</main></body></html>''')
print('Generated', len(data), 'language pages')
