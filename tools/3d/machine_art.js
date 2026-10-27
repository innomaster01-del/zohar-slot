// 2D art for the modern cabinets' default screen images (the app replaces them live), from concept2.html
const gGrad=(c,y0,y1,stops)=>{const lg=c.createLinearGradient(0,y0,0,y1);stops.forEach(([o,col])=>lg.addColorStop(o,col));return lg};
const roundRect=(c,x,y,w,h,r)=>{c.beginPath();c.roundRect(x,y,w,h,r)};
function pharaohScreen(c,w,h){
  c.fillStyle=gGrad(c,0,h,[[0,'#3b230c'],[0.5,'#6e4612'],[1,'#2a1606']]); c.fillRect(0,0,w,h);
  // temple wall hieroglyph texture
  c.globalAlpha=0.12; c.fillStyle='#ffd89a'; for(let y=20;y<h;y+=60) for(let x=10;x<w;x+=46){ c.fillRect(x,y,18,4); c.beginPath(); c.arc(x+30,y+20,7,0,7); c.fill(); c.fillRect(x+6,y+34,4,18);} c.globalAlpha=1;
  // title
  c.font='900 64px Georgia,serif'; c.textAlign='center'; c.fillStyle=gGrad(c,20,90,[[0,'#fff6c8'],[0.5,'#f2c14e'],[1,'#a8721a']]); c.shadowColor='#000'; c.shadowBlur=12; c.fillText("PHARAOH'S BOOK",w/2,78); c.shadowBlur=0;
  // reels
  const x0=60,y0=110,cw=(w-120)/5,ch=(h-210)/3;
  c.fillStyle='rgba(20,10,2,0.65)'; roundRect(c,x0-10,y0-10,w-100,ch*3+20,18); c.fill(); c.strokeStyle='#e8b84a'; c.lineWidth=6; c.stroke();
  const sym=(k,cx,cy,sz)=>{ c.save(); c.translate(cx,cy);
    const tile=(a,b)=>{ c.fillStyle=gGrad(c,-sz/2,sz/2,[[0,a],[1,b]]); roundRect(c,-sz/2,-sz/2,sz,sz,16); c.fill(); c.strokeStyle='rgba(255,220,140,0.6)'; c.lineWidth=3; c.stroke(); };
    if(k==='BOOK'){ tile('#8a1a12','#3a0804'); c.fillStyle='#f2c14e'; c.fillRect(-sz*0.3,-sz*0.32,sz*0.6,sz*0.64); c.fillStyle='#7a1a10'; c.fillRect(-sz*0.26,-sz*0.28,sz*0.52,sz*0.56); c.fillStyle='#f2c14e'; c.beginPath(); c.arc(0,0,sz*0.14,0,7); c.fill(); c.fillStyle='#7a1a10'; c.beginPath(); c.arc(0,0,sz*0.07,0,7); c.fill(); c.font=`900 ${sz*0.16}px Arial`; c.fillStyle='#fff3c0'; c.textAlign='center'; c.fillText('WILD',0,sz*0.44);}
    else if(k==='MASK'){ tile('#1f3f8a','#0a1640'); c.fillStyle='#f2c14e'; c.beginPath(); c.moveTo(-sz*0.32,-sz*0.18); c.quadraticCurveTo(0,-sz*0.52,sz*0.32,-sz*0.18); c.lineTo(sz*0.36,sz*0.32); c.lineTo(-sz*0.36,sz*0.32); c.closePath(); c.fill(); c.fillStyle='#1b3fa0'; for(let i=-3;i<=3;i++) c.fillRect(i*sz*0.1-3,-sz*0.15,6,sz*0.45); c.fillStyle='#e8c07a'; c.beginPath(); c.ellipse(0,sz*0.02,sz*0.15,sz*0.22,0,0,7); c.fill(); c.fillStyle='#111'; c.fillRect(-sz*0.1,-sz*0.04,sz*0.07,sz*0.03); c.fillRect(sz*0.03,-sz*0.04,sz*0.07,sz*0.03);}
    else if(k==='SCARAB'){ tile('#0e5a4a','#04261e'); c.fillStyle='#3fd0a8'; c.beginPath(); c.ellipse(0,sz*0.05,sz*0.2,sz*0.26,0,0,7); c.fill(); c.fillStyle='#f2c14e'; c.beginPath(); c.arc(0,-sz*0.27,sz*0.1,0,7); c.fill(); c.strokeStyle='#f2c14e'; c.lineWidth=4; for(const s2 of [-1,1]){c.beginPath(); c.moveTo(s2*sz*0.18,0); c.lineTo(s2*sz*0.4,-sz*0.1); c.lineTo(s2*sz*0.44,-sz*0.3); c.stroke();}}
    else if(k==='ANKH'){ tile('#6a2a8a','#2a0a40'); c.strokeStyle='#f2c14e'; c.lineWidth=sz*0.09; c.beginPath(); c.ellipse(0,-sz*0.2,sz*0.11,sz*0.15,0,0,7); c.stroke(); c.beginPath(); c.moveTo(-sz*0.26,-sz*0.02); c.lineTo(sz*0.26,-sz*0.02); c.moveTo(0,-sz*0.05); c.lineTo(0,sz*0.36); c.stroke();}
    else if(k==='EYE'){ tile('#a85a10','#4a2204'); c.strokeStyle='#1a3a8a'; c.lineWidth=sz*0.06; c.beginPath(); c.moveTo(-sz*0.34,0); c.quadraticCurveTo(0,-sz*0.3,sz*0.34,0); c.quadraticCurveTo(0,sz*0.2,-sz*0.34,0); c.stroke(); c.fillStyle='#1a3a8a'; c.beginPath(); c.arc(0,-sz*0.02,sz*0.08,0,7); c.fill(); c.beginPath(); c.moveTo(-sz*0.05,sz*0.1); c.lineTo(-sz*0.1,sz*0.35); c.stroke();}
    else { tile('#3a2410','#1a0f04'); c.font=`900 ${sz*0.55}px Georgia,serif`; c.textAlign='center'; c.textBaseline='middle'; const col={A:'#ff6b4a',K:'#4ac1ff',Q:'#c86bff',J:'#5adf7a','10':'#ffd24a'}[k]; c.fillStyle=col; c.strokeStyle='#2a1404'; c.lineWidth=8; c.strokeText(k,0,sz*0.04); c.fillText(k,0,sz*0.04);}
    c.restore(); };
  const grid=[['A','MASK','BOOK','K','10'],['BOOK','Q','MASK','SCARAB','EYE'],['J','ANKH','BOOK','A','Q']];
  for(let rr=0;rr<3;rr++) for(let cc=0;cc<5;cc++) sym(grid[rr][cc],x0+cw*cc+cw/2,y0+ch*rr+ch/2,Math.min(cw,ch)*0.86);
  // 3 books: free spins banner with light rays
  c.save(); c.globalCompositeOperation='lighter'; const cx=w/2,cy=y0+ch*1.5; for(let i=0;i<24;i++){ c.fillStyle=`rgba(255,200,90,${0.05})`; c.beginPath(); c.moveTo(cx,cy); const a=i/24*Math.PI*2; c.lineTo(cx+Math.cos(a)*900,cy+Math.sin(a)*900); c.lineTo(cx+Math.cos(a+0.09)*900,cy+Math.sin(a+0.09)*900); c.fill(); } c.restore();
  c.fillStyle='rgba(30,12,0,0.82)'; roundRect(c,w/2-260,y0+ch*1.5-50,520,100,20); c.fill(); c.strokeStyle='#f2c14e'; c.lineWidth=5; c.stroke();
  c.font='900 44px Georgia,serif'; c.fillStyle='#ffe08a'; c.textAlign='center'; c.textBaseline='middle'; c.fillText('10 FREE SPINS',w/2,y0+ch*1.5-12); c.font='700 24px Arial'; c.fillStyle='#fff'; c.fillText('EXPANDING SYMBOL: PHARAOH',w/2,y0+ch*1.5+28);
  // meters
  c.textBaseline='alphabetic'; c.font='700 26px Arial'; c.fillStyle='#ffd89a'; c.textAlign='left'; c.fillText('CREDIT 1,000',40,h-30); c.textAlign='center'; c.fillText('BET 10',w/2,h-30); c.textAlign='right'; c.fillText('WIN 0',w-40,h-30);
}
// ======================= DRAGON FORTUNE (Hold & Win) =======================
function dragonScreen(c,w,h){
  c.fillStyle=gGrad(c,0,h,[[0,'#5a0008'],[0.5,'#9a0a14'],[1,'#3a0004']]); c.fillRect(0,0,w,h);
  c.globalAlpha=0.08; c.strokeStyle='#ffd27a'; c.lineWidth=3; for(let y=0;y<h;y+=70) for(let x=0;x<w;x+=70){c.beginPath();c.arc(x,y,30,0,Math.PI);c.stroke();} c.globalAlpha=1;
  // jackpot bars
  const jp=[['GRAND','1,000×','#ff3a3a'],['MAJOR','250×','#c169f0'],['MINOR','50×','#4aa3ff'],['MINI','20×','#3fe08a']];
  jp.forEach(([n,v,col],i)=>{ const y=30+i*78; c.fillStyle='rgba(0,0,0,0.5)'; roundRect(c,60,y,w-120,64,32); c.fill(); c.strokeStyle=col; c.lineWidth=5; c.stroke();
    c.font='900 36px Arial'; c.fillStyle=col; c.textAlign='left'; c.textBaseline='middle'; c.fillText(n,100,y+33); c.textAlign='right'; c.fillStyle='#ffe9a8'; c.fillText(v+' BET',w-100,y+33); });
  c.font='900 70px "Noto Serif CJK SC",Georgia,serif'; c.textAlign='center'; c.textBaseline='middle'; c.fillStyle=gGrad(c,350,440,[[0,'#fff6c8'],[0.5,'#f2c14e'],[1,'#a8721a']]); c.shadowColor='#000'; c.shadowBlur=14; c.fillText('DRAGON FORTUNE',w/2,395); c.shadowBlur=0;
  // reels: hold & win in progress: coins locked
  const x0=50,y0=450,cw=(w-100)/5,ch=150;
  c.fillStyle='rgba(20,0,2,0.7)'; roundRect(c,x0-10,y0-10,w-80,ch*3+20,18); c.fill(); c.strokeStyle='#f2c14e'; c.lineWidth=6; c.stroke();
  const coins=[[0,0,'50'],[1,1,'MINI'],[2,4,'100'],[0,3,'25'],[2,1,'200'],[1,3,'MAJOR'],[0,2,'75']];
  for(let rr=0;rr<3;rr++) for(let cc=0;cc<5;cc++){ const cx=x0+cw*cc+cw/2, cy=y0+ch*rr+ch/2; const coin=coins.find(k=>k[0]===rr&&k[1]===cc);
    c.fillStyle='rgba(60,0,6,0.85)'; roundRect(c,cx-cw/2+6,cy-ch/2+6,cw-12,ch-12,14); c.fill();
    if(coin){ const gl=c.createRadialGradient(cx,cy,10,cx,cy,80); gl.addColorStop(0,'rgba(255,200,60,0.9)'); gl.addColorStop(1,'rgba(255,80,0,0)'); c.fillStyle=gl; c.fillRect(cx-80,cy-80,160,160);
      c.fillStyle=gGrad(c,cy-55,cy+55,[[0,'#fff1a8'],[0.5,'#f2b630'],[1,'#a8641a']]); c.beginPath(); c.arc(cx,cy,55,0,7); c.fill(); c.strokeStyle='#7a3a00'; c.lineWidth=5; c.stroke();
      c.font=`900 ${coin[2].length>3?28:36}px Arial`; c.fillStyle='#6a1a00'; c.fillText(coin[2],cx,cy+2); }
    else { c.font='900 60px "Noto Serif CJK SC",serif'; c.fillStyle='rgba(255,210,120,0.18)'; c.fillText('福',cx,cy+4); } }
  c.font='900 30px Arial'; c.fillStyle='#ffe9a8'; c.fillText('HOLD & WIN · RESPINS  ● ● ●',w/2,y0+ch*3+50);
  c.font='700 26px Arial'; c.fillStyle='#ffd89a'; c.textAlign='left'; c.fillText('CREDIT 1,000',40,h-30); c.textAlign='center'; c.fillText('BET 10',w/2,h-30); c.textAlign='right'; c.fillText('WIN 680',w-40,h-30);
}

function pharaohPortrait(c,w,h){
  const top=h*0.34;
  const sky=c.createLinearGradient(0,0,0,top); sky.addColorStop(0,'#1a0f2e'); sky.addColorStop(0.55,'#c2541a'); sky.addColorStop(1,'#f2b04a'); c.fillStyle=sky; c.fillRect(0,0,w,top);
  c.save(); c.globalCompositeOperation='lighter'; for(let i=0;i<30;i++){ const a=Math.PI+i/29*Math.PI; c.fillStyle='rgba(255,210,120,0.07)'; c.beginPath(); c.moveTo(w/2,top*0.78); c.lineTo(w/2+Math.cos(a)*1400,top*0.78+Math.sin(a)*1400); c.lineTo(w/2+Math.cos(a+0.05)*1400,top*0.78+Math.sin(a+0.05)*1400); c.fill(); } c.restore();
  c.fillStyle='#ffe7a0'; c.beginPath(); c.arc(w/2,top*0.78,110,0,7); c.fill();
  c.fillStyle='#2a1406'; for(const [x,s0] of [[w*0.22,240],[w*0.55,320],[w*0.85,200]]){ c.beginPath(); c.moveTo(x-s0,top); c.lineTo(x,top-s0*0.85); c.lineTo(x+s0,top); c.fill(); }
  c.font='900 96px Georgia,serif'; c.textAlign='center'; c.fillStyle=gGrad(c,top*0.18,top*0.42,[[0,'#fff6c8'],[0.5,'#f2c14e'],[1,'#a8721a']]); c.shadowColor='#000'; c.shadowBlur=18; c.fillText("PHARAOH'S",w/2,top*0.28); c.fillText('BOOK',w/2,top*0.5); c.shadowBlur=0;
  const sub=document.createElement('canvas'); sub.width=w; sub.height=Math.round(h*0.62); pharaohScreen(sub.getContext('2d'),sub.width,sub.height); c.drawImage(sub,0,95,w,sub.height-95,0,top,w,sub.height);
  c.fillStyle='#0d0703'; c.fillRect(0,top+sub.height,w,h-top-sub.height);
  // virtual button deck at the bottom of the J
  const by=top+sub.height+20; ['MENU','BET −','BET +','AUTO'].forEach((t,i)=>{ c.fillStyle='rgba(242,193,78,0.15)'; roundRect(c,40+i*150,by,130,70,35); c.fill(); c.strokeStyle='#f2c14e'; c.lineWidth=3; c.stroke(); c.font='700 26px Arial'; c.fillStyle='#ffe2a0'; c.textAlign='center'; c.textBaseline='middle'; c.fillText(t,105+i*150,by+36); });
  const sg=c.createRadialGradient(w-130,by+35,10,w-130,by+35,80); sg.addColorStop(0,'#fff0b0'); sg.addColorStop(1,'#d48a10'); c.fillStyle=sg; c.beginPath(); c.arc(w-130,by+35,62,0,7); c.fill(); c.font='900 30px Arial'; c.fillStyle='#3a1a00'; c.fillText('SPIN',w-130,by+37);
}
function dragonMain(c,w,h){
  c.fillStyle=gGrad(c,0,h,[[0,'#3a0004'],[0.5,'#8a0810'],[1,'#2a0003']]); c.fillRect(0,0,w,h);
  // dragon body swirling behind the reels
  c.save(); c.globalAlpha=0.28; c.strokeStyle='#ffcf5a'; c.lineWidth=60; c.lineCap='round'; c.beginPath(); c.moveTo(-50,300); c.bezierCurveTo(300,0,700,500,950,200); c.stroke(); c.lineWidth=40; c.beginPath(); c.moveTo(-50,1350); c.bezierCurveTo(400,1100,500,1600,950,1350); c.stroke(); c.restore();
  c.font='900 110px "Noto Serif CJK SC",Georgia,serif'; c.textAlign='center'; c.textBaseline='middle'; c.fillStyle=gGrad(c,90,240,[[0,'#fff6c8'],[0.5,'#f2c14e'],[1,'#a8721a']]); c.shadowColor='#000'; c.shadowBlur=18; c.fillText('DRAGON',w/2,150); c.fillText('FORTUNE',w/2,270); c.shadowBlur=0;
  const x0=40,y0=380,cw=(w-80)/5,ch=230;
  c.fillStyle='rgba(20,0,2,0.72)'; roundRect(c,x0-10,y0-10,w-60,ch*3+20,22); c.fill(); c.strokeStyle='#f2c14e'; c.lineWidth=7; c.stroke();
  const coins=[[0,0,'50'],[1,1,'MINI'],[2,4,'100'],[0,3,'25'],[2,1,'200'],[1,3,'MAJOR'],[0,2,'75'],[2,2,'40']];
  for(let rr=0;rr<3;rr++) for(let cc=0;cc<5;cc++){ const cx=x0+cw*cc+cw/2, cy=y0+ch*rr+ch/2; const coin=coins.find(k=>k[0]===rr&&k[1]===cc);
    c.fillStyle='rgba(70,0,8,0.9)'; roundRect(c,cx-cw/2+7,cy-ch/2+7,cw-14,ch-14,16); c.fill();
    if(coin){ const gl=c.createRadialGradient(cx,cy,10,cx,cy,110); gl.addColorStop(0,'rgba(255,200,60,0.95)'); gl.addColorStop(1,'rgba(255,80,0,0)'); c.fillStyle=gl; c.fillRect(cx-110,cy-110,220,220);
      c.fillStyle=gGrad(c,cy-70,cy+70,[[0,'#fff1a8'],[0.5,'#f2b630'],[1,'#a8641a']]); c.beginPath(); c.arc(cx,cy,72,0,7); c.fill(); c.strokeStyle='#7a3a00'; c.lineWidth=6; c.stroke();
      c.font=`900 ${coin[2].length>3?34:46}px Arial`; c.fillStyle='#6a1a00'; c.fillText(coin[2],cx,cy+2); }
    else { c.font='900 90px "Noto Serif CJK SC",serif'; c.fillStyle='rgba(255,210,120,0.2)'; c.fillText('福',cx,cy+6); } }
  c.font='900 44px Arial'; c.fillStyle='#ffe9a8'; c.fillText('HOLD & WIN  ·  RESPINS  ● ● ●',w/2,y0+ch*3+70);
  c.font='700 34px Arial'; c.fillStyle='#ffd89a'; c.textAlign='left'; c.fillText('CREDIT 1,000',40,h-50); c.textAlign='center'; c.fillText('BET 10',w/2,h-50); c.textAlign='right'; c.fillText('WIN 690',w-40,h-50);
}
function dragonTopper(c,w,h){
  c.fillStyle=gGrad(c,0,h,[[0,'#1a0204'],[1,'#4a0008']]); c.fillRect(0,0,w,h);
  c.font='900 300px "Noto Serif CJK SC",serif'; c.textAlign='center'; c.textBaseline='middle'; c.fillStyle='rgba(255,200,80,0.12)'; c.fillText('龍',w*0.5,h*0.55);
  const jp=[['GRAND','1,000×','#ff3a3a'],['MAJOR','250×','#c169f0'],['MINOR','50×','#4aa3ff'],['MINI','20×','#3fe08a']];
  jp.forEach(([n,v,col],i)=>{ const x=40+(i%2)*(w/2), y=60+Math.floor(i/2)*170; c.fillStyle='rgba(0,0,0,0.55)'; roundRect(c,x,y,w/2-80,140,70); c.fill(); c.strokeStyle=col; c.lineWidth=7; c.stroke();
    c.font='900 54px Arial'; c.fillStyle=col; c.textAlign='left'; c.fillText(n,x+50,y+72); c.textAlign='right'; c.fillStyle='#ffe9a8'; c.fillText(v,x+w/2-130,y+72); });
  c.font='900 40px Arial'; c.textAlign='center'; c.fillStyle='#ffd27a'; c.fillText('JACKPOTS  ×  BET',w/2,h-50);
}

export {pharaohPortrait, dragonMain, dragonTopper, gGrad, roundRect};
