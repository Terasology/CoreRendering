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

Enabling only animated grass equals the "Minimal" graphics preset.
Animated grass can be enabled with the following config adjustment:
```json5
{
  "animateGrass": true
}
```

The following screenshots show the graphic setting baseline to the left and only the "Animate Grass" effect enabled to the right.

<fig src="_media/img/graphic-effects_baseline.png">Baseline (left) and "Animate Grass" Effect (right)</fig>

## Animate Water

## Bloom

## Blur

## Clamp Lighting

## Cloud Shadows

## Extra Lighting

## Film Grain

## Fog

## Light Shafts

## Outline

## Parallax Mapping

## Shadows

## SSAO

## Vignette

## Volumetric Fog

## Water Reflections