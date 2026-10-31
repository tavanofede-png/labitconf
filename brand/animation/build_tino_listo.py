import bpy
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "tino_listo_source.png"
FRAMES = ROOT / "frames"
BLEND = ROOT / "tino_listo.blend"

FRAMES.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)

scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = 512
scene.render.resolution_y = 512
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.render.film_transparent = True
scene.render.fps = 24
scene.frame_start = 1
scene.frame_end = 48
scene.render.filepath = str(FRAMES / "frame_")

bpy.ops.object.camera_add(location=(0, 0, 10))
camera = bpy.context.object
camera.name = "TINO_Listo_Camera"
camera.data.type = "ORTHO"
camera.data.ortho_scale = 2.55
scene.camera = camera

bpy.ops.mesh.primitive_plane_add(size=2, location=(0, 0, 0))
mascot = bpy.context.object
mascot.name = "TINO_OK_Listo"

material = bpy.data.materials.new("TINO_Listo_Transparent")
material.use_nodes = True
nodes = material.node_tree.nodes
links = material.node_tree.links
nodes.clear()

output = nodes.new("ShaderNodeOutputMaterial")
mix = nodes.new("ShaderNodeMixShader")
transparent = nodes.new("ShaderNodeBsdfTransparent")
emission = nodes.new("ShaderNodeEmission")
texture = nodes.new("ShaderNodeTexImage")
alpha_multiply = nodes.new("ShaderNodeMath")
alpha_multiply.operation = "MULTIPLY"
visibility = nodes.new("ShaderNodeValue")
visibility.name = "Animated visibility"

image = bpy.data.images.load(str(SOURCE), check_existing=False)
image.colorspace_settings.name = "sRGB"
texture.image = image
texture.interpolation = "Linear"

links.new(texture.outputs["Color"], emission.inputs["Color"])
links.new(texture.outputs["Alpha"], alpha_multiply.inputs[0])
links.new(visibility.outputs[0], alpha_multiply.inputs[1])
links.new(alpha_multiply.outputs[0], mix.inputs[0])
links.new(transparent.outputs[0], mix.inputs[1])
links.new(emission.outputs[0], mix.inputs[2])
links.new(mix.outputs[0], output.inputs[0])
mascot.data.materials.append(material)

def keyframe(frame, scale, y, rotation_degrees, opacity):
    mascot.scale = (scale, scale, scale)
    mascot.location.y = y
    mascot.rotation_euler.z = math.radians(rotation_degrees)
    mascot.keyframe_insert("scale", frame=frame)
    mascot.keyframe_insert("location", frame=frame)
    mascot.keyframe_insert("rotation_euler", frame=frame)
    visibility.outputs[0].default_value = opacity
    visibility.outputs[0].keyframe_insert("default_value", frame=frame)

keyframe(1, 0.72, -0.24, -7, 0.0)
keyframe(8, 1.08, 0.05, 3, 1.0)
keyframe(14, 0.96, -0.02, -2, 1.0)
keyframe(20, 1.0, 0.0, 0, 1.0)
keyframe(27, 1.0, 0.06, 2.5, 1.0)
keyframe(34, 1.0, 0.0, -2, 1.0)
keyframe(41, 1.0, 0.035, 1, 1.0)
keyframe(48, 1.0, 0.0, 0, 1.0)

scene["usage"] = "Reusable transparent completion animation for the final slide of every TINO onboarding."
scene["copy"] = "¡Listo!"
scene["duration_seconds"] = 2.0

bpy.ops.wm.save_as_mainfile(filepath=str(BLEND))
bpy.ops.render.render(animation=True)
