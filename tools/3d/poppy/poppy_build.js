const C=(hex)=>new THREE.Color(hex).convertSRGBToLinear();
let dirty=true;

/* ================= sound: tiny synthesized voice and effects ================= */
let sound=true,actx=null;
function ac(){if(!actx){try{actx=new AudioContext();}catch(e){}}return actx;}
function tone(type,f0,f1,dur,vol,delay=0){const a=ac();if(!a||!sound)return;const t=a.currentTime+delay,o=a.createOscillator(),g=a.createGain();o.type=type;o.frequency.setValueAtTime(f0,t);o.frequency.exponentialRampToValueAtTime(Math.max(20,f1),t+dur);
  g.gain.setValueAtTime(0.0001,t);g.gain.exponentialRampToValueAtTime(vol,t+0.012);g.gain.exponentialRampToValueAtTime(0.0001,t+dur);o.connect(g).connect(a.destination);o.start(t);o.stop(t+dur+0.02);}
function noise(dur,vol,fType,f0,f1,delay=0){const a=ac();if(!a||!sound)return;const t=a.currentTime+delay,b=a.createBuffer(1,Math.floor(a.sampleRate*dur),a.sampleRate),d=b.getChannelData(0);for(let i=0;i<d.length;i++)d[i]=Math.random()*2-1;
  const s=a.createBufferSource();s.buffer=b;const f=a.createBiquadFilter();f.type=fType;f.frequency.setValueAtTime(f0,t);f.frequency.exponentialRampToValueAtTime(Math.max(30,f1),t+dur);const g=a.createGain();
  g.gain.setValueAtTime(0.0001,t);g.gain.exponentialRampToValueAtTime(vol,t+0.01);g.gain.exponentialRampToValueAtTime(0.0001,t+dur);s.connect(f).connect(g).connect(a.destination);s.start(t);s.stop(t+dur);}
let voiceBase=560;
const sfx={
  voice:(n)=>{for(let i=0;i<n;i++){const f=voiceBase*rnd(0.85,1.35);tone('triangle',f,f*rnd(0.8,1.25),0.075,0.07,i*0.085);}},
  boing:()=>tone('sine',220,660,0.22,0.12),
  land:(k=1)=>{tone('sine',160,60,0.12,0.12*k);noise(0.08,0.05*k,'lowpass',600,200);},
  chomp:()=>{noise(0.07,0.12,'lowpass',1800,500);noise(0.06,0.09,'lowpass',1500,400,0.12);},
  yum:()=>{tone('sine',520,780,0.12,0.08);tone('sine',780,1040,0.14,0.07,0.12);},
  yuck:()=>{tone('square',330,140,0.35,0.05);},
  giggle:()=>{for(let i=0;i<5;i++)tone('sine',voiceBase*1.4+i*60,voiceBase*1.7+i*60,0.06,0.06,i*0.07);},
  purr:()=>{const a=ac();if(!a||!sound)return;const t=a.currentTime,o=a.createOscillator(),g=a.createGain(),l=a.createOscillator(),lg=a.createGain();o.type='sawtooth';o.frequency.value=48;
    l.frequency.value=22;lg.gain.value=0.025;l.connect(lg).connect(g.gain);g.gain.value=0.03;const f=a.createBiquadFilter();f.type='lowpass';f.frequency.value=300;o.connect(f).connect(g).connect(a.destination);o.start(t);l.start(t);o.stop(t+0.9);l.stop(t+0.9);},
  poof:()=>noise(0.4,0.12,'bandpass',1800,300),
  sparkle:()=>{for(let i=0;i<6;i++)tone('sine',1200+i*220,1500+i*220,0.09,0.04,i*0.06);},
  ouch:()=>{tone('sawtooth',700,300,0.18,0.05);},
  snore:()=>noise(1.2,0.03,'lowpass',400,180),
  fire:()=>noise(1.1,0.1,'lowpass',2500,300),
  ufo:()=>{const a=ac();if(!a||!sound)return;const t=a.currentTime,o=a.createOscillator(),g=a.createGain(),l=a.createOscillator(),lg=a.createGain();o.frequency.value=520;l.frequency.value=7;lg.gain.value=60;l.connect(lg).connect(o.frequency);
    g.gain.setValueAtTime(0.0001,t);g.gain.exponentialRampToValueAtTime(0.05,t+0.3);g.gain.exponentialRampToValueAtTime(0.0001,t+3);o.connect(g).connect(a.destination);o.start(t);l.start(t);o.stop(t+3);l.stop(t+3);},
  laugh:()=>{for(let i=0;i<4;i++)tone('sawtooth',voiceBase*0.7-i*20,voiceBase*0.5-i*20,0.12,0.05,i*0.16);},
};

/* ================= fur ================= */
// Real fur look: the body is drawn many times, each layer a bit bigger, with more and more holes (shell fur).
function noiseTex(){
  // one strand per 2x2 block; around the face the fur is short so the eyes and mouth stay clear
  const w=512,h=256,c=mk(w,h),g=c.getContext('2d'),d=g.createImageData(w,h);
  for(let y=0;y<h;y+=2)for(let x=0;x<w;x+=2){
    const fx=(x-128)/62,fy=(y-134)/46,r=Math.hypot(fx,fy),q=r<1?Math.pow(r,3):1;
    const v=255*(r<1?(0.2*(1-q)+Math.random()*q):Math.random());
    for(let k=0;k<4;k++){const i=((y+(k>>1))*w+x+(k&1))*4;d.data[i]=d.data[i+1]=d.data[i+2]=v;d.data[i+3]=255;}}
  g.putImageData(d,0,0);
  const t=new THREE.CanvasTexture(c);t.wrapS=t.wrapT=THREE.RepeatWrapping;t.magFilter=THREE.NearestFilter;t.minFilter=THREE.LinearFilter;t.generateMipmaps=false;return t;}
const NOISE=noiseTex();
const SHELLS=14,FL=0.2;
function furMaterials(){const mats=[];for(let i=0;i<SHELLS;i++){const m=new THREE.MeshStandardMaterial({color:0xffffff,roughness:0.95,metalness:0,envMapIntensity:0.3});
  if(i>0){m.alphaMap=NOISE;m.alphaTest=0.06+0.86*Math.pow(i/(SHELLS-1),0.85);}mats.push(m);}return mats;}
function paintFur(mats,hex,opacity){const base=new THREE.Color(hex).convertSRGBToLinear();
  mats.forEach((m,i)=>{const k=0.62+0.42*Math.pow(i/(SHELLS-1),0.8);m.color.copy(base).multiplyScalar(k);
    if(opacity<1){m.transparent=true;m.opacity=opacity*(i===0?0.6:1);m.depthWrite=false;}else{m.transparent=false;m.opacity=1;m.depthWrite=true;}m.needsUpdate=true;});}
function furBall(mats,geo,scale,n,group){const out=[];for(let i=0;i<n;i++){const si=Math.round(i*(SHELLS-1)/Math.max(1,n-1));const m=new THREE.Mesh(geo,mats[si]);
  const f=1+FL*(i/Math.max(1,n-1));m.scale.set(scale[0]*f,scale[1]*f,scale[2]*f);m.renderOrder=i;group.add(m);out.push(m);}return out;}

/* ================= the friend ================= */
const SPH=new THREE.SphereGeometry(1,48,32),SPH_S=new THREE.SphereGeometry(1,20,14);
function arcTube(r,a0,a1,th){const pts=[];for(let i=0;i<=16;i++){const a=a0+(a1-a0)*i/16;pts.push(V(Math.cos(a)*r,Math.sin(a)*r,0));}return new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts),24,th,6,false);}
function spiralTex(){const c=mk(128,128),g=c.getContext('2d');g.strokeStyle='#1c1622';g.lineWidth=7;g.lineCap='round';g.beginPath();
  for(let i=0;i<=120;i++){const a=i/120*Math.PI*6,r=4+i*0.48;const x=64+Math.cos(a)*r,y=64+Math.sin(a)*r;if(i)g.lineTo(x,y);else g.moveTo(x,y);}g.stroke();const t=new THREE.CanvasTexture(c);return t;}
const SPIRAL=spiralTex();
const COLORS={boy:0x63aef0,girl:0xf484bd};
function makePet(gender){
  const P={gender,root:new THREE.Group(),body:new THREE.Group()};
  const root=P.root,body=P.body;root.add(body);
  const fur=furMaterials();P.fur=fur;P.baseColor=COLORS[gender]||COLORS.girl;
  const R=0.5,CY=0.5;
  // body
  const bodyG=new THREE.Group();bodyG.position.y=CY;body.add(bodyG);P.bodyG=bodyG;
  P.bodyShells=furBall(fur,SPH,[R,R*0.95,R*0.92],SHELLS,bodyG);
  // tail
  const tail=new THREE.Group();tail.position.set(0,-0.22,-0.43);bodyG.add(tail);furBall(fur,SPH_S,[0.11,0.11,0.11],6,tail);
  // feet and arms
  const footMat=new THREE.MeshStandardMaterial({color:0xffffff,roughness:0.85});P.footMat=footMat;
  P.feet=[-1,1].map(s=>{const f=new THREE.Mesh(SPH_S,footMat);f.scale.set(0.13,0.075,0.17);f.position.set(s*0.18,0.055,0.12);body.add(f);return f;});
  P.arms=[-1,1].map(s=>{const g=new THREE.Group();g.position.set(s*0.44,0.44,0.06);body.add(g);const inner=new THREE.Group();inner.position.set(s*0.04,-0.08,0);g.add(inner);furBall(fur,SPH_S,[0.085,0.12,0.09],5,inner);return g;});
  // ears: long and soft, with a pink inside
  const innerMat=new THREE.MeshStandardMaterial({color:C(gender==='girl'?0xffd0e6:0xd2ebff),roughness:0.8});
  P.ears=[-1,1].map(s=>{const g=new THREE.Group();g.position.set(s*0.2,0.88,-0.02);g.rotation.z=-s*0.28;body.add(g);
    const e=new THREE.Group();e.position.y=0.26;g.add(e);furBall(fur,SPH_S,[0.12,0.31,0.08],7,e);
    const inn=new THREE.Mesh(SPH_S,innerMat);inn.scale.set(0.06,0.22,0.02);inn.position.set(0,0.0,0.075);e.add(inn);
    return {g,base:-s*0.28,s,a:0,v:0};});
  // face
  const face=new THREE.Group();face.position.y=CY;body.add(face);P.face=face;
  const eyeMat=new THREE.MeshPhysicalMaterial({color:0x0b070d,roughness:0.25,metalness:0,clearcoat:1,clearcoatRoughness:0.1,envMapIntensity:0.5});
  const white=new THREE.MeshBasicMaterial({color:0xffffff});
  const dark=new THREE.MeshBasicMaterial({color:0x2a1a24});
  P.eyes=[-1,1].map(s=>{const g=new THREE.Group();g.position.set(s*0.165,0.06,0.5);g.rotation.y=s*0.3;face.add(g);
    const ball=new THREE.Mesh(SPH_S,eyeMat);ball.scale.set(0.1,0.128,0.055);g.add(ball);
    const h1=new THREE.Mesh(SPH_S,white);h1.scale.setScalar(0.03);h1.position.set(0.03,0.042,0.05);g.add(h1);
    const h2=new THREE.Mesh(SPH_S,white);h2.scale.setScalar(0.015);h2.position.set(-0.03,-0.042,0.05);g.add(h2);
    const happy=new THREE.Mesh(arcTube(0.06,0.25,Math.PI-0.25,0.013),dark);happy.position.set(0,-0.02,0.03);happy.visible=false;g.add(happy);
    const closed=new THREE.Mesh(arcTube(0.06,Math.PI+0.3,Math.PI*2-0.3,0.012),dark);closed.position.set(0,0.035,0.03);closed.visible=false;g.add(closed);
    const spiral=new THREE.Mesh(new THREE.PlaneGeometry(0.17,0.17),new THREE.MeshBasicMaterial({map:SPIRAL,transparent:true}));spiral.position.z=0.06;spiral.visible=false;g.add(spiral);
    let lashes=null;
    if(gender==='girl'){lashes=new THREE.Group();g.add(lashes);for(let k=0;k<3;k++){const l=new THREE.Mesh(new THREE.BoxGeometry(0.008,0.04,0.008),dark);
      const a=(s>0?0.35:Math.PI-0.35)+(s>0?1:-1)*k*0.32;l.position.set(Math.cos(a)*0.085,Math.sin(a)*0.1+0.01,0.02);l.rotation.z=a-Math.PI/2;lashes.add(l);}}
    return {g,ball,h1,h2,happy,closed,spiral,lashes,s};});
  // cheeks, nose, mouths
  const blush=new THREE.MeshBasicMaterial({color:0xff7ba8,transparent:true,opacity:0.45,depthWrite:false});
  [-1,1].forEach(s=>{const c=new THREE.Mesh(new THREE.CircleGeometry(0.055,20),blush);c.scale.y=0.6;c.position.set(s*0.27,-0.06,0.47);c.rotation.y=s*0.55;face.add(c);});
  const nose=new THREE.Mesh(SPH_S,new THREE.MeshStandardMaterial({color:C(0xd9557f),roughness:0.4}));nose.scale.set(0.028,0.02,0.018);nose.position.set(0,-0.03,0.55);face.add(nose);
  const mouthG=new THREE.Group();mouthG.position.set(0,-0.11,0.548);mouthG.scale.setScalar(1.3);face.add(mouthG);P.mouthG=mouthG;
  const mouthDark=new THREE.MeshBasicMaterial({color:0x5a1e2c}),tongueM=new THREE.MeshBasicMaterial({color:0xff7f9a});
  const M={};
  M.smile=new THREE.Mesh(arcTube(0.055,Math.PI+0.55,Math.PI*2-0.55,0.01),dark);M.smile.position.y=0.035;
  M.frown=new THREE.Mesh(arcTube(0.05,0.6,Math.PI-0.6,0.01),dark);M.frown.position.y=-0.04;
  M.flat=new THREE.Mesh(new THREE.BoxGeometry(0.06,0.012,0.01),dark);
  {const sh=new THREE.Shape();sh.moveTo(-0.06,0.01);sh.quadraticCurveTo(0,0.0,0.06,0.01);sh.quadraticCurveTo(0.05,-0.07,0,-0.075);sh.quadraticCurveTo(-0.05,-0.07,-0.06,0.01);
    const g=new THREE.Group();g.add(new THREE.Mesh(new THREE.ShapeGeometry(sh),mouthDark));const t=new THREE.Mesh(new THREE.CircleGeometry(0.03,16),tongueM);t.scale.y=0.6;t.position.set(0,-0.052,0.001);g.add(t);M.grin=g;}
  {const g=new THREE.Group();const o=new THREE.Mesh(new THREE.CircleGeometry(0.04,20),mouthDark);g.add(o);const t=new THREE.Mesh(new THREE.CircleGeometry(0.022,14),tongueM);t.scale.y=0.55;t.position.set(0,-0.022,0.001);g.add(t);M.o=g;}
  {const g=new THREE.Group();const o=new THREE.Mesh(new THREE.CircleGeometry(0.03,16),mouthDark);o.scale.y=0.5;g.add(o);const t=new THREE.Mesh(SPH_S,tongueM);t.scale.set(0.022,0.035,0.01);t.position.set(0.01,-0.035,0.005);g.add(t);M.yuck=g;}
  for(const k in M){M[k].visible=false;mouthG.add(M[k]);}
  P.mouths=M;
  // boy: a little tuft of hair; girl: a bow
  if(gender==='girl'){const bow=new THREE.Group();bow.position.set(-0.24,0.96,0.12);bow.rotation.z=0.35;body.add(bow);const bm=new THREE.MeshStandardMaterial({color:C(0xff4f8b),roughness:0.45});
    [-1,1].forEach(s=>{const c=new THREE.Mesh(new THREE.ConeGeometry(0.06,0.12,16),bm);c.rotation.z=s*Math.PI/2;c.position.x=s*0.06;c.scale.z=0.45;bow.add(c);});
    const k=new THREE.Mesh(SPH_S,bm);k.scale.setScalar(0.035);bow.add(k);}
  else{const tuft=new THREE.Group();tuft.position.set(0,0.97,0.05);body.add(tuft);const tm=new THREE.MeshStandardMaterial({color:C(0x3f86cc),roughness:0.8});P.tuftMat=tm;for(let i=0;i<3;i++){const c=new THREE.Mesh(new THREE.ConeGeometry(0.05,0.2,12),tm);c.position.x=(i-1)*0.055;c.rotation.z=(1-i)*0.5+0.15;c.position.y=0.07;c.rotation.x=-0.2;tuft.add(c);}}
  // shadow on the floor
  const sc=mk(64,64),g=sc.getContext('2d'),rg=g.createRadialGradient(32,32,2,32,32,32);rg.addColorStop(0,'rgba(0,0,0,.45)');rg.addColorStop(1,'rgba(0,0,0,0)');g.fillStyle=rg;g.fillRect(0,0,64,64);
  P.shadow=new THREE.Mesh(new THREE.PlaneGeometry(1,1),new THREE.MeshBasicMaterial({map:new THREE.CanvasTexture(sc),transparent:true,depthWrite:false}));scene.add(P.shadow);
  P.props={};
  P.st={x:3,y:0,vx:0,vy:0,dir:1,yaw:0,yawT:0,sq:0,sqv:0,lean:0,leanT:0,pitch:0,lie:0,lieT:0,lift:0,walk:0,walking:false,blink:0,blinkAt:2,
    eyes:'open',mouth:'smile',look:V(),lookT:V(),armT:[0,0],arm:[0,0],held:false,onGround:true,scale:1,shake:0,spin:0,opacity:1,float:0,armsForward:false};
  paintFur(fur,P.baseColor,1);footMat.color.copy(new THREE.Color(P.baseColor).convertSRGBToLinear().multiplyScalar(0.62));
  scene.add(root);
  return P;
}
function setFace(P,eyes,mouth){if(eyes)P.st.eyes=eyes;if(mouth)P.st.mouth=mouth;}
