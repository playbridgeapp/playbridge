#!/usr/bin/env python3
"""Generate an isolated XCTest runner; never modify the shipping Xcode project."""
import pathlib
import plistlib
import sys

root = pathlib.Path(sys.argv[1])
source = str(pathlib.Path(__file__).with_name('BrowserPopupUITests.swift').resolve())
objects = {}
def add(key, isa, **fields):
    objects[key] = dict(isa=isa, **fields)
    return key
add('SOURCE', 'PBXFileReference', path=source, sourceTree='<absolute>', lastKnownFileType='sourcecode.swift')
add('PRODUCT', 'PBXFileReference', path='PopupTests.xctest', sourceTree='BUILT_PRODUCTS_DIR', explicitFileType='wrapper.cfbundle')
add('BUILD', 'PBXBuildFile', fileRef='SOURCE')
add('SOURCES', 'PBXSourcesBuildPhase', buildActionMask=2147483647, files=['BUILD'], runOnlyForDeploymentPostprocessing=0)
add('GROUP', 'PBXGroup', children=['SOURCE', 'PRODUCT'], sourceTree='<group>')
settings = dict(SDKROOT='iphoneos', IPHONEOS_DEPLOYMENT_TARGET='16.0', SWIFT_VERSION='5.0',
                GENERATE_INFOPLIST_FILE='YES', PRODUCT_NAME='$(TARGET_NAME)',
                PRODUCT_BUNDLE_IDENTIFIER='com.playbridge.popup-uitests',
                TARGETED_DEVICE_FAMILY='1', CODE_SIGNING_ALLOWED='NO')
add('CONFIG', 'XCBuildConfiguration', name='Debug', buildSettings=settings)
add('CONFIGS', 'XCConfigurationList', buildConfigurations=['CONFIG'], defaultConfigurationName='Debug', defaultConfigurationIsVisible=0)
add('TARGET', 'PBXNativeTarget', name='PopupTests', productName='PopupTests', productReference='PRODUCT',
    productType='com.apple.product-type.bundle.ui-testing', buildConfigurationList='CONFIGS',
    buildPhases=['SOURCES'], buildRules=[], dependencies=[])
add('PROJECT', 'PBXProject', mainGroup='GROUP', targets=['TARGET'], buildConfigurationList='CONFIGS',
    compatibilityVersion='Xcode 14.0', projectDirPath='', projectRoot='', developmentRegion='en', knownRegions=['en'])
project = root / 'PopupTests.xcodeproj'
project.mkdir()
(project / 'project.pbxproj').write_bytes(plistlib.dumps(dict(archiveVersion='1', objectVersion='56', objects=objects, rootObject='PROJECT')))
