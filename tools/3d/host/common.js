import * as THREE from 'three'; import {FBXLoader} from 'three/addons/loaders/FBXLoader.js'; import {mergeVertices} from 'three/addons/utils/BufferGeometryUtils.js';
export const KEEP_MORPHS=['AA_VI_00_Sil','AA_VI_01_PP','AA_VI_02_FF','AA_VI_03_TH','AA_VI_04_DD','AA_VI_05_KK','AA_VI_06_CH','AA_VI_07_SS','AA_VI_08_nn','AA_VI_09_RR','AA_VI_10_aa','AA_VI_11_E','AA_VI_12_I','AA_VI_13_O','AA_VI_14_U','AK_09_EyeBlinkLeft','AK_10_EyeBlinkRight','AK_44_MouthSmileLeft','AK_45_MouthSmileRight','AK_03_BrowInnerUp','AK_25_JawOpen','AK_07_CheekSquintLeft','AK_08_CheekSquintRight'];
const fbx=new FBXLoader(); export const load=u=>new Promise((res,rej)=>fbx.load(u,res,undefined,rej));
const tl=new THREE.TextureLoader(); export const pending=[]; const tex=(u,srgb)=>{const t=tl.load(u); pending.push(new Promise(r=>{const i=setInterval(()=>{if(t.image&&t.image.complete){clearInterval(i);r()}},50)})); t.colorSpace=srgb?THREE.SRGBColorSpace:THREE.NoColorSpace; t.flipY=true; t.name=u; t.userData.mimeType='image/jpeg'; return t};
export async function avatar(id='m015', file='Business_Male_04_facial.fbx'){
  const o=await load(file); o.scale.setScalar(+(new URLSearchParams(location.search).get('scale')||0.01));
  o.traverse(n=>{ if(!n.isMesh) return; n.frustumCulled=false; n.castShadow=true;
    const mats=(Array.isArray(n.material)?n.material:[n.material]).map(m=>{ const part=m.name.includes('head')?'head':m.name.includes('opacity')?'opacity':'body';
      if(part==='opacity'){ const t=tex(`${id}_opacity.png`,true); t.userData.mimeType='image/png'; return new THREE.MeshStandardMaterial({name:m.name, map:t, alphaTest:0.45, side:THREE.DoubleSide, roughness:0.55, metalness:0}); }
      return new THREE.MeshPhysicalMaterial({name:m.name, map:tex(`${id}_${part}_color.jpg`,true), normalMap:tex(`${id}_${part}_normal.jpg`,false), roughness:part==='head'?0.5:0.62, metalness:0, sheen:part==='body'?0.35:0, sheenColor:new THREE.Color(0xff8090), sheenRoughness:0.5}) });
    n.material=Array.isArray(n.material)?mats:mats[0];
    // keep only the morph targets we use
    if(n.morphTargetDictionary){ const g=n.geometry; const dict=n.morphTargetDictionary; const keep=[]; const names=[];
      for(const k of KEEP_MORPHS){ const full=Object.keys(dict).find(x=>x.endsWith('.'+k)); if(full!==undefined){keep.push(dict[full]); names.push(k)} }
      g.morphAttributes.position=keep.map(i=>g.morphAttributes.position[i]); delete g.morphAttributes.normal;
      n.morphTargetDictionary=Object.fromEntries(names.map((k,i)=>[k,i])); n.morphTargetInfluences=names.map(()=>0); }
    if(!n.geometry.index){ const before=n.geometry.attributes.position.count; const groups=n.geometry.groups.slice(); n.geometry=mergeVertices(n.geometry,1e-5); n.geometry.groups.length||groups.forEach(gp=>n.geometry.addGroup(gp.start,gp.count,gp.materialIndex)); window.MERGE=[before,n.geometry.attributes.position.count,n.geometry.groups.length]; }
  });
  return o;
}
export async function clip(file,name){ const o=await load(file); o.scale.setScalar(+(new URLSearchParams(location.search).get('scale')||0.01)); const c=o.animations[0]; c.name=name;
  c.tracks=c.tracks.filter(t=>!t.name.includes('Footsteps')&&!t.name.endsWith('.scale')&&!(t.name.startsWith('Bip01.'))&&!/Bip01_(M|R|L)?(Eye|Mouth|Jaw|Lip|Tongue|Caninus|Masseter|Cheek|Eyebrow|InnerEyebrow|OuterEyebrow|MiddleEyebrow|Nose|Upperlip|UpperLip|BottomLip)/.test(t.name.split('.')[0]));   // the face stays as modelled (mocap face bones break the mouth)
  // keep the dealer on his spot: pelvis may bob up/down but not wander sideways or forward
  for(const t of c.tracks){ if(t.name==='Bip01_Pelvis.position'){ const v=t.values; const x0=v[0], z0=v[2]; for(let i=0;i<v.length;i+=3){ v[i]=x0+(v[i]-x0)*0.15; v[i+2]=z0+(v[i+2]-z0)*0.15; } } }
  return c; }
