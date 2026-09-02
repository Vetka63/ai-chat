<script setup>
defineProps({
  themeId: {
    type: String,
    required: true,
  },
})

const autumnLeaves = [
  { glyph: '🍁', left: '3%', delay: '-2s', duration: '17s', size: '18px', drift: '42px' },
  { glyph: '🍂', left: '12%', delay: '-11s', duration: '21s', size: '15px', drift: '-35px' },
  { glyph: '🍁', left: '23%', delay: '-6s', duration: '19s', size: '13px', drift: '54px' },
  { glyph: '🍂', left: '37%', delay: '-15s', duration: '23s', size: '16px', drift: '-48px' },
  { glyph: '🍁', left: '56%', delay: '-9s', duration: '20s', size: '17px', drift: '38px' },
  { glyph: '🍂', left: '69%', delay: '-4s', duration: '18s', size: '14px', drift: '-52px' },
  { glyph: '🍁', left: '82%', delay: '-13s', duration: '24s', size: '20px', drift: '44px' },
  { glyph: '🍂', left: '94%', delay: '-7s', duration: '22s', size: '13px', drift: '-38px' },
]

const winterSnowflakes = [
  { left: '2%', delay: '-8s', duration: '18s', size: '13px', drift: '36px', opacity: 0.48 },
  { left: '8%', delay: '-2s', duration: '14s', size: '8px', drift: '-24px', opacity: 0.7 },
  { left: '15%', delay: '-12s', duration: '21s', size: '17px', drift: '48px', opacity: 0.34 },
  { left: '22%', delay: '-5s', duration: '16s', size: '10px', drift: '-38px', opacity: 0.62 },
  { left: '30%', delay: '-15s', duration: '23s', size: '7px', drift: '28px', opacity: 0.76 },
  { left: '38%', delay: '-9s', duration: '19s', size: '14px', drift: '-44px', opacity: 0.42 },
  { left: '47%', delay: '-3s', duration: '15s', size: '9px', drift: '34px', opacity: 0.68 },
  { left: '55%', delay: '-17s', duration: '24s', size: '18px', drift: '-52px', opacity: 0.3 },
  { left: '63%', delay: '-6s', duration: '17s', size: '11px', drift: '42px', opacity: 0.58 },
  { left: '71%', delay: '-13s', duration: '22s', size: '8px', drift: '-30px', opacity: 0.72 },
  { left: '79%', delay: '-4s', duration: '16s', size: '15px', drift: '50px', opacity: 0.4 },
  { left: '87%', delay: '-10s', duration: '20s', size: '10px', drift: '-40px', opacity: 0.65 },
  { left: '95%', delay: '-1s', duration: '14s', size: '7px', drift: '26px', opacity: 0.78 },
]

const winterLights = [
  '#ffd166', '#ef6b73', '#79d7c5', '#8cb8ff', '#ffd166', '#ef6b73',
  '#79d7c5', '#8cb8ff', '#ffd166', '#ef6b73', '#79d7c5', '#8cb8ff',
]
</script>

<template>
  <div v-if="themeId === 'autumn'" class="seasonal-effects seasonal-effects--autumn" aria-hidden="true">
    <span class="autumn-haze autumn-haze--left"></span>
    <span class="autumn-haze autumn-haze--right"></span>
    <span class="autumn-moon">☾</span>
    <span
      v-for="(leaf, index) in autumnLeaves"
      :key="index"
      class="autumn-leaf"
      :style="{
        '--leaf-left': leaf.left,
        '--leaf-delay': leaf.delay,
        '--leaf-duration': leaf.duration,
        '--leaf-size': leaf.size,
        '--leaf-drift': leaf.drift,
      }"
    >
      {{ leaf.glyph }}
    </span>
  </div>

  <div v-else-if="themeId === 'winter'" class="seasonal-effects seasonal-effects--winter" aria-hidden="true">
    <span class="winter-aurora winter-aurora--left"></span>
    <span class="winter-aurora winter-aurora--right"></span>

    <div class="winter-garland">
      <span
        v-for="(color, index) in winterLights"
        :key="color + index"
        class="winter-light"
        :style="{ '--light-color': color, '--light-delay': (index * -0.17) + 's' }"
      >
        ✦
      </span>
    </div>

    <span
      v-for="(snowflake, index) in winterSnowflakes"
      :key="index"
      class="winter-snowflake"
      :style="{
        '--snow-left': snowflake.left,
        '--snow-delay': snowflake.delay,
        '--snow-duration': snowflake.duration,
        '--snow-size': snowflake.size,
        '--snow-drift': snowflake.drift,
        '--snow-opacity': snowflake.opacity,
      }"
    >
      ❄
    </span>

    <div class="winter-scene">
      <span class="winter-scene__tree">🎄</span>
      <span class="winter-scene__snowman">⛄</span>
      <span class="winter-scene__gift winter-scene__gift--one">🎁</span>
      <span class="winter-scene__gift winter-scene__gift--two">🎁</span>
    </div>
  </div>
</template>
