# Graphics Effects

As a baseline for the following explanations, all graphics effects were disabled.
This way, the effect of the individual settings can be shown in isolation, without being strengthened, lessened, or cancelled by other effects.
This minimal setup (not to confuse with the "Minimal" preset) results in the following `config.cfg` section for `rendering` (omitting settings not related to in-game graphic effects):

```json5
{
  "rendering": {
    "chunkLods": 0.0,
    "flickeringLight": false,
    "animateGrass": false,
    "animateWater": false,
    "blurIntensity": 0,
    "reflectiveWater": false,
    "vignette": false,
    "motionBlur": false,
    "ssao": false,
    "filmGrain": false,
    "outline": false,
    "lightShafts": false,
    "bloom": false,
    "dynamicShadows": false,
    "maxChunksUsedForShadowMapping": 1024,
    "shadowMapResolution": 4096,
    "normalMapping": false,
    "parallaxMapping": false,
    "dynamicShadowsPcfFiltering": false,
    "cloudShadows": false,
    "particleEffectLimit": 10,
    "meshLimit": 400,
    "inscattering": false,
    "localReflections": false,
    "clampLighting": false,
    "volumetricFog": false
  }
}
```

The following screenshot shows the graphic effects baseline with all graphic effects disabled.

<fig src="_media/img/graphic-effects_baseline.png">Baseline</fig>


## Animate Grass

The "Animate Grass" effect lets decorations such as grass and flowers sway in the wind.
Enabling only animated grass equals the "Minimal" graphics preset.
Animated grass can be enabled with the following config adjustment:
```json5
{
  "animateGrass": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Animate Grass" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline_close-up.png" src2="_media/img/graphic-effects_animate-grass_close-up.png">Baseline (left) and "Animate Grass" Effect (right)</fig-side-by-side>


## Animate Water

The "Animate Water" effect creates waves on blocks of water.
Animated grass can be enabled with the following config adjustment:
```json5
{
  "animateWater": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Animate Water" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_animate-water.png">Baseline (left) and "Animate Water" Effect (right)</fig-side-by-side>


## Bloom

The "Bloom" effect produces fringes or feathers of light extending from the borders of bright areas.
Bloom can be enabled with the following config adjustment:
```json5
{
  "bloom": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Bloom" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_bloom.png">Baseline (left) and "Bloom" Effect (right)</fig-side-by-side>


## Blur

As the name suggests, the "Blur" effect blurs the image.
Terasology supports three levels of blur: "Some", "Normal", "Max".
The respective levels can be enabled with the following config adjustment:
```json5
{
  "blurIntensity": lvl // with lvl = 1 for "Some", lvl = 2 for "Normal", lvl = 3 for "Max" 
}
```

The following screenshots show the graphic setting baseline to the left and only the "Bloom" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_blur-some.png">Baseline (left) and "Blur" Effect on Level "Some" (right)</fig-side-by-side>
<fig-side-by-side src1="_media/img/graphic-effects_blur-normal.png" src2="_media/img/graphic-effects_blur-max.png">"Blur" Effect on Level "Normal" (left) and "Blur" Effect on Level "Max" (right)</fig-side-by-side>


## Clamp Lighting

Clamp Lighting can be enabled with the following config adjustment:
```json5
{
  "clampLighting": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Clamp Lighting" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_clamp-lighting.png">Baseline (left) and "Clamp Lighting" Effect (right)</fig-side-by-side>


## Cloud Shadows

Cloud Shadows can be enabled with the following config adjustment:
```json5
{
  "cloudShadows": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Cloud Shadows" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_cloud-shadows.png">Baseline (left) and "Cloud Shadows" Effect (right)</fig-side-by-side>


## Extra Lighting

Extra Lighting can be enabled with the following config adjustment:
```json5
{
  "normalMapping": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Extra Lighting" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_extra-lighting.png">Baseline (left) and "Extra Lighting" Effect (right)</fig-side-by-side>


## Film Grain

Film Grain can be enabled with the following config adjustment:
```json5
{
  "filmGrain": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Film Grain" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_film-grain.png">Baseline (left) and "Film Grain" Effect (right)</fig-side-by-side>


## Fog

Fog can be enabled with the following config adjustment:
```json5
{
  "inscattering": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Fog" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_fog.png">Baseline (left) and "Fog" Effect (right)</fig-side-by-side>


## Light Shafts

Light Shafts can be enabled with the following config adjustment:
```json5
{
  "lightShafts": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Light Shafts" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_light-shafts.png">Baseline (left) and "Light Shafts" Effect (right)</fig-side-by-side>


## Outline

Outline can be enabled with the following config adjustment:
```json5
{
  "outline": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Outline" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_outline.png">Baseline (left) and "Outline" Effect (right)</fig-side-by-side>


## Parallax Mapping

Parallax Mapping can be enabled with the following config adjustment:
```json5
{
  "parallaxMapping": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Parallax Mapping" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_parallax-mapping.png">Baseline (left) and "Parallax Mapping" Effect (right)</fig-side-by-side>


## Shadows

Terasology supports two modes of shadows: "On" and "PCR".
Shadows in mode "On" can be enabled with the following config adjustment:
```json5
{
  "dynamicShadows": true
}
```

Shadows in mode "PCR" can be enabled with the following config adjustment:
```json5
{
  "dynamicShadowsPcfFiltering": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Shadows" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_shadows-on.png">Baseline (left) and "Shadows" Effect in mode "On" (right)</fig-side-by-side>
<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_shadows-PCR.png">Baseline (left) and "Shadows" Effect in mode "PCR" (right)</fig-side-by-side>


## SSAO

SSAO can be enabled with the following config adjustment:
```json5
{
  "ssao": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "SSAO" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_SSAO.png">Baseline (left) and "SSAO" Effect (right)</fig-side-by-side>


## Vignette

Vignette can be enabled with the following config adjustment:
```json5
{
  "vignette": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Vignette" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_vignette.png">Baseline (left) and "Vignette" Effect (right)</fig-side-by-side>


## Volumetric Fog

Volumetric Fog can be enabled with the following config adjustment:
```json5
{
  "volumetricFog": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Volumetric Fog" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_volumetric-fog.png">Baseline (left) and "Volumetric Fog" Effect (right)</fig-side-by-side>


## Water Reflections

Terasology supports two modes of water reflections: "Global" and "SSR".
Water Reflections in mode "Global" can be enabled with the following config adjustment:
```json5
{
  "reflectiveWater": true
}
```

Water Reflections in mode "SSR" can be enabled with the following config adjustment:
```json5
{
  "localReflections": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Water Reflections" effect enabled to the right.

<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_water-reflections-global.png">Baseline (left) and "Water Reflections" Effect in mode "Global" (right)</fig-side-by-side>
<fig-side-by-side src1="_media/img/graphic-effects_baseline.png" src2="_media/img/graphic-effects_water-reflections-SSR.png">Baseline (left) and "Water Reflections" Effect in mode "SSR" (right)</fig-side-by-side>
