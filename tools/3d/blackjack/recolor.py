import numpy as np
from PIL import Image
im=np.asarray(Image.open('/home/claude/microsoft/microsoft-rocketbox/Assets/Avatars/Professions/Business_Male_04/Textures/m015_body_color.tga').convert('RGB')).astype(np.float32)/255
H,W,_=im.shape; out=im.copy()
L=im.mean(2); S=im.max(2)-im.min(2)
yy,xx=np.mgrid[0:H,0:W]/H*700  # coordinates in the 700 preview scale
def box(x0,y0,x1,y1): return (xx>=x0)&(xx<x1)&(yy>=y0)&(yy<y1)
skin=(im[...,0]>im[...,2]+0.08)&(im[...,0]>0.35)
trousers=(box(0,0,198,295)|box(502,0,700,295))&~skin
sleeves=(box(0,295,198,595)|box(502,295,700,595))&~skin
torso=box(198,30,502,600)&~skin
def white(m, base=0.62, gain=0.55):
    n=np.clip((L-0.18)/0.35,0,1); v=base+gain*(n-0.5)*0.8
    for c,w in enumerate((1.0,0.995,0.98)): out[...,c]=np.where(m,np.clip(v*w+0.16,0,1),out[...,c])
def black(m, k=0.42, tint=(1.0,1.0,1.04)):
    for c in range(3): out[...,c]=np.where(m,np.clip(L*k*tint[c],0,1),out[...,c])
white(sleeves)
black(trousers,0.45)
# torso: the shirt (lighter) shows in the V and the collar, the vest is darker
lv=Image.fromarray((L*255).astype(np.uint8)).filter(__import__('PIL.ImageFilter',fromlist=['x']).GaussianBlur(6))
Lb=np.asarray(lv)/255.
from PIL import ImageFilter
f=(im[...,1]-im[...,2])+2*(im[...,1]-im[...,0])
fb=np.asarray(Image.fromarray(np.clip((f+0.1)*1000,0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(8)))/1000.-0.1
shirt=torso&(((((fb>0.03)&(Lb>0.24))|(Lb>0.33))&(box(198,30,502,300)|box(300,300,400,520)))|box(198,30,242,300)|box(458,30,502,300)|((Lb>0.2)&(box(198,300,224,430)|box(476,300,502,430))))&~((Lb<0.15))
white(shirt); black(torso&~shirt,0.38)
Image.fromarray((out*255).astype(np.uint8)).resize((1024,1024),Image.LANCZOS).save('dealer_body_color.jpg',quality=92)
Image.fromarray((out*255).astype(np.uint8)).resize((700,700)).save('/tmp/claude-0/-home-claude/3ce76260-695a-5316-a475-36cbf67d8b0f/scratchpad/body_tex2.png')
