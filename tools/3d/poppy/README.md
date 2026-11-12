# Follow Poppy: 3D models

Everything in `app/src/walk/assets/models` is made by these scripts with three.js r128 (the copy inside the Pufi desktop
pet; any `three.min.js` r128 works):

    node export_poppy.js three.min.js poppy_build.js girl ../../../app/src/walk/assets/models/poppy_girl.glb
    node export_poppy.js three.min.js poppy_build.js boy  ../../../app/src/walk/assets/models/poppy_boy.glb
    node make_trail.js   three.min.js ../../../app/src/walk/assets/models     # ribbon, chevron, paw prints, shadow
    python3 render_glb.py ../../../app/src/walk/assets/models/poppy_girl.glb preview.png   # quick look without a GPU

- `poppy_build.js` is Poppy's original build code (`makePet`) from the desktop pet.
- Poppy's GLB has the parts the app moves (Body, Face, ArmL/ArmR, FootL/FootR, EarL/EarR), every face in its own
  node (EyeOpen/EyeHappy/EyeClosed L/R, MouthSmile/MouthGrin/MouthO; hidden ones at scale 0.0001), and two clips:
  `walk` (0.56 s) and `idle` (2.4 s).
- `gltf_writer.js` is the small GLB writer both scripts use.
