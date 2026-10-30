const fs = require('node:fs');
const path = require('node:path');
const browser = path.join(__dirname, '../PlayBridge Phone/PlayBridge Phone/Browser');
const hosts = JSON.parse(fs.readFileSync(path.join(browser, 'BrowserCastingPolicy.swift'), 'utf8')
  .match(/static let blockedHosts = (\[[\s\S]*?\])/)[1].replace(/,\s*]/, ']'));
const source = fs.readFileSync(path.join(browser, 'DetectionScript.swift'), 'utf8')
  .match(/static let source = #"""\n([\s\S]*?)\n\s*"""#/)[1]
  .replace('\\#(BrowserCastingPolicy.hostsJSON)', JSON.stringify(hosts));
module.exports = { source: process.env.PB_DETECTION_SCRIPT ? fs.readFileSync(process.env.PB_DETECTION_SCRIPT, 'utf8') : source, hosts };
