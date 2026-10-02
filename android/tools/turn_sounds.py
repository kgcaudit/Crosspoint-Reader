#!/usr/bin/env python3
"""넘김 소리 세 가지(사락 · 휙 · 톡)를 합성해 ui-design/src/main/res/raw 에 쓴다.

외부 녹음을 쓰지 않는 까닭: 무료 소리 사이트의 사용 조건을 확인할 수 없었고(0.32.0 조사), 앱이 직접 만든 소리는
따질 것이 없다. 씨앗(seed)이 고정이라 다시 돌려도 같은 파일이 나온다 — 사용자가 구상안에서 들은 그 소리다.

    python3 tools/turn_sounds.py        # android/ 에서. numpy · scipy 필요
"""
import os
import numpy as np
from scipy.signal import butter, sosfilt
from scipy.io import wavfile

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'ui-design', 'src', 'main', 'res', 'raw')
rng = np.random.default_rng(7)


def bp(x, lo, hi, o=2): return sosfilt(butter(o, [lo, hi], btype='band', fs=SR, output='sos'), x)


def env(n, att, dec, shape=2.0):
    t = np.arange(n) / SR
    a = np.clip(t / att, 0, 1) ** 1.5
    d = np.exp(-np.clip(t - att, 0, None) / dec * shape)
    return a * d


def crackle(n, density):
    # 종이가 휘며 나는 잔 바스락: 드문드문 작은 충격을 고역 필터로.
    imp = np.zeros(n)
    k = int(density * n / SR)
    idx = rng.integers(0, n, k)
    imp[idx] = rng.uniform(0.3, 1.0, k) * rng.choice([-1, 1], k)
    return bp(imp, 2500, 9000)


def flap(n, at, f=180, dur=0.05):
    # 종이가 내려앉는 '툭': 낮은 쿵 + 짧은 바람.
    t = np.arange(n) / SR
    x = np.zeros(n)
    i = int(at * SR)
    tt = t[i:] - t[i]
    x[i:] = np.sin(2 * np.pi * f * tt) * np.exp(-tt / dur * 4) * 0.6
    x[i:] += bp(rng.standard_normal(n - i), 300, 1500) * np.exp(-tt / 0.03) * 0.4
    return x


def norm(x, peak=0.5):
    x = x - np.mean(x)
    return x / np.max(np.abs(x)) * peak


def fade(x, ms=8):
    k = int(ms * SR / 1000)
    x[:k] *= np.linspace(0, 1, k)
    x[-k:] *= np.linspace(1, 0, k)
    return x


def save(name, x): wavfile.write(os.path.join(OUT, name), SR, (fade(x) * 32767).astype(np.int16))


# 사락: 부드러운 바스락, 0.30초
n = int(0.30 * SR)
noise = bp(rng.standard_normal(n), 900, 6500) * env(n, 0.08, 0.22)
a = noise * 0.8 + crackle(n, 180) * env(n, 0.06, 0.25) * 1.2
save('turn_rustle.wav', norm(a, 0.45))

# 휙: 공기를 가르는 휙 + 바스락 + 끝에 내려앉는 툭, 0.42초
n = int(0.42 * SR)
t = np.arange(n) / SR
w = rng.standard_normal(n)
sw = (bp(w, 600, 1800) * np.clip(1 - t / 0.15, 0, 1)
      + bp(w, 1500, 5000) * np.exp(-((t - 0.17) / 0.08) ** 2)
      + bp(w, 900, 3000) * np.clip((t - 0.2) / 0.15, 0, 1) * np.exp(-np.clip(t - 0.3, 0, None) / 0.05))
b = sw * env(n, 0.12, 0.3, 1.2) + crackle(n, 120) * env(n, 0.1, 0.2) * 0.9 + flap(n, 0.33, 170, 0.06)
save('turn_swish.wav', norm(b, 0.5))

# 톡: 아주 짧게 종이를 톡, 0.09초
n = int(0.09 * SR)
c = bp(rng.standard_normal(n), 1200, 4000) * env(n, 0.004, 0.025) + flap(n, 0.0, 220, 0.03) * 0.7
save('turn_tap.wav', norm(c, 0.35))
