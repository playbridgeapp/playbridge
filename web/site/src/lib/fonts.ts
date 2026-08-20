import serif600 from '@fontsource/ibm-plex-serif/files/ibm-plex-serif-latin-600-normal.woff2?url';
import sans400 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-400-normal.woff2?url';
import sans500 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-500-normal.woff2?url';
import sans600 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-600-normal.woff2?url';
import mono400 from '@fontsource/jetbrains-mono/files/jetbrains-mono-latin-400-normal.woff2?url';
import mono500 from '@fontsource/jetbrains-mono/files/jetbrains-mono-latin-500-normal.woff2?url';

export const fontFiles = {
  serif600,
  sans400,
  sans500,
  sans600,
  mono400,
  mono500
};

export const fontFaceCss = `
@font-face{font-family:'IBM Plex Serif';font-style:normal;font-weight:600;font-display:optional;src:url('${serif600}') format('woff2')}
@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:400;font-display:optional;src:url('${sans400}') format('woff2')}
@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:500;font-display:optional;src:url('${sans500}') format('woff2')}
@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:600;font-display:optional;src:url('${sans600}') format('woff2')}
@font-face{font-family:'JetBrains Mono';font-style:normal;font-weight:400;font-display:optional;src:url('${mono400}') format('woff2')}
@font-face{font-family:'JetBrains Mono';font-style:normal;font-weight:500;font-display:optional;src:url('${mono500}') format('woff2')}
`.trim();
