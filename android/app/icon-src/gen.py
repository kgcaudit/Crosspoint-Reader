import json
BG="#D4704C"; FG="#EEEDEB"
# 펼친 책. 가운데 골을 두고 두 쪽. 위아래 가장자리는 살짝 휜다.
L,C1,C2,R = 26.0, 52.8, 55.6, 82.0      # 왼쪽 끝, 골 왼쪽, 골 오른쪽, 오른쪽 끝
T,B,SAG = 40.0, 75.0, 3.2               # 위, 아래, 가장자리 휨
def page(x0,x1,inner_left):
    # inner(골) 쪽이 더 내려가고 바깥쪽이 올라간다
    yi_t, yo_t = T+SAG*0.9, T
    yi_b, yo_b = B+SAG*0.6, B-SAG*0.4
    if inner_left:   # 오른쪽 페이지: 골이 왼쪽(x0)
        a=(x0,yi_t); b=(x1,yo_t); c=(x1,yo_b); d=(x0,yi_b)
    else:            # 왼쪽 페이지: 골이 오른쪽(x1)
        a=(x0,yo_t); b=(x1,yi_t); c=(x1,yi_b); d=(x0,yo_b)
    mx=(x0+x1)/2
    return (f"M{a[0]},{a[1]} C{mx-5},{T-3.2} {mx+5},{T-3.2} {b[0]},{b[1]} "
            f"L{c[0]},{c[1]} C{mx+5},{B-2.6} {mx-5},{B-2.6} {d[0]},{d[1]} Z")
left=page(L,C1,False); right=page(C2,R,True)
k=0.50                     # 찢는 선 기울기(dx/dy) — '/' 방향
g=2.2                      # 찢긴 틈
def band(b1,b2):
    top,bot=20,90; d=(bot-top)*k
    return f"M{b1+g/2:.2f},{bot} L{b2-g/2:.2f},{bot} L{b2-g/2+d:.2f},{top} L{b1+g/2+d:.2f},{top} Z"
# 아래(y=90) 기준 x. 첫 띠는 온전한 부분.
cuts=[20, 49.0, 55.5, 61.5, 100]
lift=[(0,0,0,0,0),(0.5,-2.0,3,58,76),(1.2,-4.4,6,64,76),(2.0,-7.2,9,70,76)]
groups=[]
for i,(a,b) in enumerate(zip(cuts,cuts[1:])):
    tx,ty,r,px,py=lift[i]; groups.append(dict(clip=band(a,b),tx=tx,ty=ty,rot=r,px=px,py=py))
json.dump(dict(BG=BG,FG=FG,left=left,right=right,groups=groups),open('icon.json','w'),indent=1)
svg=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">',
     f'<rect width="108" height="108" fill="{BG}"/>',f'<path d="{left}" fill="{FG}"/>']
for i,gr in enumerate(groups):
    svg.append(f'<clipPath id="c{i}"><path d="{gr["clip"]}"/></clipPath>')
    svg.append(f'<g transform="translate({gr["tx"]},{gr["ty"]}) rotate({gr["rot"]} {gr["px"]} {gr["py"]})"><path d="{right}" fill="{FG}" clip-path="url(#c{i})"/></g>')
svg.append('</svg>')
open('icon.svg','w').write('\n'.join(svg))
