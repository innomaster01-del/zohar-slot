# Restyle Rocketbox Business_Female_04 (MIT) as a live-casino wheel host: deep red satin top, black trousers, black silk
# blouse in the neckline, red lipstick and soft evening eye make-up. Writes host_body_color.jpg / host_head_color.jpg.
import numpy as np
from PIL import Image, ImageFilter
B=np.asarray(Image.open('f020_body_color.jpg').convert('RGB')).astype(np.float32)/255
H,W,_=B.shape; yy,xx=np.mgrid[0:H,0:W]/H*700
def box(x0,y0,x1,y1): return (xx>=x0)&(xx<x1)&(yy>=y0)&(yy<y1)
L=B.mean(2)
skin=(B[...,0]>B[...,2]+0.10)&(B[...,0]>0.30)
cuff=(np.abs(B[...,0]-B[...,2])<0.05)&(L>0.45)                     # grey shirt cuffs and the blouse triangle
blouse=box(318,345,382,445)&~skin&(L>0.3)
cloth=((L<0.45)|cuff)&~skin&~blouse
# remove the pinstripes: smooth the shading of the cloth
Lc=np.where(cuff,L*0.3,L)
Lm=np.asarray(Image.fromarray((Lc*255).astype(np.uint8)).filter(ImageFilter.MedianFilter(9)).filter(ImageFilter.GaussianBlur(3)))/255.
shade=np.clip((Lm-0.02)/0.22,0,1)                                    # folds 0..1
trousers=(box(0,478,240,700)|box(470,478,700,700)|box(240,560,470,700))&cloth
top=cloth&~trousers
out=B.copy()
red=np.array([0.50,0.035,0.07]); hi=np.array([0.95,0.25,0.30])
shade=np.where(cuff,0.45,shade)
v=0.35+0.9*shade
for c in range(3):
    out[...,c]=np.where(top, np.clip(red[c]*v+hi[c]*np.clip(shade-0.75,0,1)*0.8,0,1), out[...,c])
    out[...,c]=np.where(trousers, np.clip(0.05+0.12*shade,0,1)*(1.0 if c<2 else 1.08), out[...,c])
    out[...,c]=np.where(blouse, np.clip(0.03+0.10*L,0,1)*(1.0 if c<2 else 1.1), out[...,c])
Image.fromarray((out*255).astype(np.uint8)).resize((1024,1024),Image.LANCZOS).save('host_body_color.jpg',quality=92)
Image.fromarray((out*255).astype(np.uint8)).resize((700,700)).save('/tmp/claude-0/-home-claude/3ce76260-695a-5316-a475-36cbf67d8b0f/scratchpad/hbody.png')
# ---- face: lipstick and eyes ----
Hd=np.asarray(Image.open('f020_head_color.jpg').convert('RGB')).astype(np.float32)/255
h=Hd.shape[0]; yy,xx=np.mgrid[0:h,0:h]/h*500
Y,X=np.mgrid[0:h,0:h]
rg=(Hd[...,0]-Hd[...,1])*255
lips=(rg>60)&((((X-1032)/128.)**2+((Y-836)/46.)**2)<1)
m=np.asarray(Image.fromarray((Lc*255).astype(np.uint8)).filter(ImageFilter.MedianFilter(9)).filter(ImageFilter.GaussianBlur(3)))/255.
shade=np.clip((Lm-0.02)/0.22,0,1)                                    # folds 0..1
trousers=(box(0,478,240,700)|box(470,478,700,700)|box(240,560,470,700))&cloth
top=cloth&~trousers
out=B.copy()
red=np.array([0.50,0.035,0.07]); hi=np.array([0.95,0.25,0.30])
shade=np.where(cuff,0.45,shade)
v=0.35+0.9*shade
for c in range(3):
    out[...,c]=np.where(top, np.clip(red[c]*v+hi[c]*np.clip(shade-0.75,0,1)*0.8,0,1), out[...,c])
    out[...,c]=np.where(trousers, np.clip(0.05+0.12*shade,0,1)*(1.0 if c<2 else 1.08), out[...,c])
    out[...,c]=np.where(blouse, np.clip(0.03+0.10*L,0,1)*(1.0 if c<2 else 1.1), out[...,c])
Image.fromarray((out*255).astype(np.uint8)).resize((1024,1024),Image.LANCZOS).save('host_body_color.jpg',quality=92)
Image.fromarray((out*255).astype(np.uint8)).resize((700,700)).save('/tmp/claude-0/-home-claude/3ce76260-695a-5316-a475-36cbf67d8b0f/scratchpad/hbody.png')
# ---- face: lipstick and eyes ----
Hd=np.asarray(Image.open('f020_head_color.jpg').convert('RGB')).astype(np.float32)/255
h=Hd.shape[0]; yy,xx=np.mgrid[0:h,0:h]/h*500
lips=((((xx-250)/22)**2+((yy-201)/4.6)**2)<1)|((((xx-250)/19)**2+((yy-209.5)/5.8)**2)<1)
m=np.asarray(Image.fromarray((lips*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.5)))/255.
o=Hd.copy(); Lh=Hd.mean(2)
lip=np.stack([0.62*(0.6+0.8*Lh),0.06*(0.6+0.8*Lh),0.10*(0.6+0.8*Lh)],-1)
tintc=np.array([1.05,0.32,0.36])
o=o*(1-m[...,None]*0.8)+np.clip(Hd*tintc*1.05,0,1)*(m[...,None]*0.8)
# eyes: a soft dark smoky line over the lids
for cx in (213,287):
    d=np.sqrt(((xx-cx)/26)**2+((yy-133)/9)**2)
    k=np.clip(1-d,0,1)**1.5*0.45
    o=o*(1-k[...,None])+np.array([0.10,0.06,0.07])*k[...,None]
# a little warmer cheeks
for cx in (205,295):
    d=np.sqrt(((xx-cx)/30)**2+((yy-175)/18)**2); k=np.clip(1-d,0,1)*0.12
    o=o*(1-k[...,None])+np.array([0.85,0.45,0.45])*k[...,None]
Image.fromarray((np.clip(o,0,1)*255).astype(np.uint8)).resize((1024,1024),Image.LANCZOS).save('host_head_color.jpg',quality=93)
Image.fromarray((np.clip(o,0,1)*255).astype(np.uint8)).resize((500,500)).save('/tmp/claude-0/-home-claude/3ce76260-695a-5316-a475-36cbf67d8b0f/scratchpad/hhead.png')
for n in ('body','head'): Image.open(f'f020_{n}_normal.jpg').resize((1024,1024),Image.LANCZOS).save(f'host_{n}_normal.jpg',quality=90)
