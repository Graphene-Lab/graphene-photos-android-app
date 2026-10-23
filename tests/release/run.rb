# One entry point for the mocked Ruby/Fastlane suite. Integration checks run separately.
Dir.glob(File.join(__dir__, '*_test.rb')).sort.each { |path| require path }
