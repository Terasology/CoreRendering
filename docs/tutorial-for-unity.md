# Terminology for Unity Developers

## What is an FBO?

FBO stands for frame object buffer. It's equal to the renderer target that we generated.  

## What is a node?

You can regard a node as a "Render Pass" in Unity.  
A node is custom code that will be applied to the render result.  
For example, you can change the output of **InitialPostProcessingNode** to achieve a tinting effect.  
You can also change the input of "HighPassNode" to apply a "blooming" effect or create a node rendering only a character's outline and combine it with the output of **InitialPostProcessingNode** to create an XRay node.  
_Note: This requires technology for a rendering-specific layer. Currently, it's unclear, whether Terasology supports this kind of  "layer" concept._

## How nodes work

a node has Inputs and Outputs, it's input are result from previous node, and it's output can be next node's input.  

For example, a highPass node uses `_SceneColor` as input, then a bloom node uses both `_SceneColor` and the highpass node's output as input.  

The Unity equivalent of `_SceneColor` is the scene texture.  
The highpass node is used to grab a light area and send it to the blur node to achieve the bright area of a bloom effect.  
It's being used for **InitialPostProcessingNode** as the input for the final FBO.  

## How to create a FBO

Setting up FBOs in Terasology translates to Unity as setting up rendering targets:

```C#
RenderTexture  computeTex = new RenderTexture (IMG_WIDTH, IMG_HEIGHT, 24);

```

In Terasology, FBOs can be set up using setDependencies(Context context):

```java
//the root Fbo for everything
DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
//create the fbo with scale
FBO someFbo = requiresFbo(new FboConfig(FBO_URI, HALF_SCALE, FBO.Type.DEFAULT), displayResolutionDependentFBOs);
//create the fbo with width and height
FBO someFbo2 = requiresFbo(new FboConfig(FBO_URI, IMG_WIDTH, IMG_HEIGHT, FBO.Type.NO_COLOR), displayResolutionDependentFBOs);
```

## Where are FBO being processed?

The FBO is being process inside the process method of a node.
1. Fetch the material by `var mat = getMaterial(MATERIAL_URN)`
2. Set up material properties at `process()`
3. Render the quad

### What is the quad? Where Did i used the material?

Well, a quad is a quad that you created at ctor
and you need to activate the material in `setDependencies` like this:

```java
addDesiredStateChange(new EnableMaterial(MATERIAL_URN));
```

If you study the code deeply, you'll find that, `setDependencies(Content ctx)` is setting up OpenGL contents and then `process()` is the actual rendering logic: they are just OpenGL code wrapped into java code.

## Common Issues

### Unrecongnized Shader

If you wrote a shader, but Terasology doesn't recognize it, you probably forgot to create a material.

1. create a material called "id.mat".  

```
{
  "shader": "MODULE:ID",
  "params": {}
}
```

2. write 2 shaders `ID_vert.glsl` and `ID_frag.glsl`.
3. now Terasology should recognize your shader.