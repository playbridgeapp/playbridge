const fs = require('fs');
const path = require('path');

const target = process.argv[2];
if (!['firefox', 'chrome'].includes(target)) {
    console.error('Usage: node build.js [firefox|chrome]');
    process.exit(1);
}

const srcDir = path.join(__dirname, 'src');
const distDir = path.join(__dirname, 'dist', target);

// Clean and create dist dir
if (fs.existsSync(distDir)) {
    fs.rmSync(distDir, { recursive: true, force: true });
}
fs.mkdirSync(distDir, { recursive: true });

// Helper to copy recursively
function copyRecursively(src, dest) {
    const stats = fs.statSync(src);
    if (stats.isDirectory()) {
        fs.mkdirSync(dest, { recursive: true });
        for (const file of fs.readdirSync(src)) {
            copyRecursively(path.join(src, file), path.join(dest, file));
        }
    } else {
        fs.copyFileSync(src, dest);
    }
}

// 1. Copy all files
for (const file of fs.readdirSync(srcDir)) {
    if (file === 'manifest.json') continue; // Handled separately
    copyRecursively(path.join(srcDir, file), path.join(distDir, file));
}

// 2. Process manifest
const baseManifest = JSON.parse(fs.readFileSync(path.join(srcDir, 'manifest.json'), 'utf8'));

if (target === 'firefox') {
    // Manifest V2 for GeckoView / Firefox
    baseManifest.manifest_version = 2;
    baseManifest.background = {
        scripts: ["hls-parser.js", "background.js"],
        "persistent": true
    };
    
    // Convert host_permissions to V2 format
    if (baseManifest.host_permissions) {
        baseManifest.permissions = [...baseManifest.permissions, ...baseManifest.host_permissions];
        delete baseManifest.host_permissions;
    }
    
    // web_accessible_resources V2 format
    if (baseManifest.web_accessible_resources) {
        baseManifest.web_accessible_resources = baseManifest.web_accessible_resources.flatMap(r => r.resources);
    }
    
    // webRequestBlocking is supported in V2
    baseManifest.permissions.push("webRequestBlocking");

    // Both Firefox and GeckoView (Android) require an extension ID to be loaded correctly
    baseManifest.browser_specific_settings = {
        gecko: {
            id: "video-detector@playbridge",
            strict_min_version: "102.0"
        }
    };
    
    // MV2 uses browser_action for icon click
    baseManifest.browser_action = {
        default_popup: "ui/popup.html",
        default_icon: "icon.png"
    };
} else if (target === 'chrome') {
    // Manifest V3 for Chrome
    baseManifest.manifest_version = 3;
    baseManifest.background = {
        service_worker: "background.js"
    };
    
    // MV3 uses action for icon click
    baseManifest.action = {
        default_popup: "ui/popup.html",
        default_icon: "icon.png"
    };
}

fs.writeFileSync(
    path.join(distDir, 'manifest.json'),
    JSON.stringify(baseManifest, null, 4)
);

console.log(`Built extension for ${target} at ${distDir}`);
