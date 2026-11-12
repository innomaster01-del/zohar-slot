# Poppy (Follow Poppy)

`poppy_build.js` is Poppy's original build code (`makePet`) from the desktop pet (Pufi). `export_poppy.js` runs it with
three.js r128 (the copy inside the desktop pet) and writes a GLB with a looping `walk` clip (feet, arms, ears, body bob):

    node export_poppy.js three.min.js poppy_build.js girl ../../../app/src/walk/assets/models/poppy_girl.glb
    node export_poppy.js three.min.js poppy_build.js boy  ../../../app/src/walk/assets/models/poppy_boy.glb
    python3 render_glb.py ../../../app/src/walk/assets/models/poppy_girl.glb preview.png   # quick look without a GPU
