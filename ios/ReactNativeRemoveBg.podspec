require 'json'

# Read package.json relative to this podspec so the path is correct no matter
# where CocoaPods resolves the symlink from.
package_path = File.expand_path(File.join(__dir__, '..', 'package.json'))
package = JSON.parse(File.read(package_path))

Pod::Spec.new do |s|
  s.name           = 'ReactNativeRemoveBg'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.license        = package['license']
  s.platforms      = { :ios => '16.0' }
  s.source         = { git: 'https://github.com/LordJayanta/react-native-removebg' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.frameworks = 'Vision', 'ImageIO', 'CoreGraphics'

  s.source_files = "**/*.{h,m,mm,swift,hpp,cpp}"
end