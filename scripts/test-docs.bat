@echo off
REM Veridot Documentation Build and Test Script for Windows
REM This script tests the Jekyll build locally before GitHub Pages deployment

echo 🚀 Testing Veridot Documentation Build...

REM Check if Jekyll is installed
where jekyll >nul 2>nul
if %errorlevel% neq 0 (
    echo ❌ Jekyll not found. Installing...
    gem install jekyll bundler
    if %errorlevel% neq 0 (
        echo ❌ Failed to install Jekyll. Please install Ruby and RubyGems first.
        pause
        exit /b 1
    )
)

REM Install dependencies
echo 📦 Installing dependencies...
call bundle install
if %errorlevel% neq 0 (
    echo ❌ Failed to install dependencies.
    pause
    exit /b 1
)

REM Build the site
echo 🔨 Building Jekyll site...
call bundle exec jekyll build
if %errorlevel% neq 0 (
    echo ❌ Build failed. Please check the Jekyll configuration.
    pause
    exit /b 1
)

echo ✅ Build successful!

REM Check for required files
echo 🔍 Verifying documentation files...

set "all_files_exist=true"
set "files=_site\index.html _site\docs\getting-started.html _site\docs\java-guide.html _site\docs\nodejs-guide.html _site\docs\api-reference.html _site\docs\security.html _site\assets\main.css"

for %%f in (%files%) do (
    if exist "%%f" (
        echo ✅ Found: %%f
    ) else (
        echo ❌ Missing: %%f
        set "all_files_exist=false"
    )
)

if "%all_files_exist%"=="true" (
    echo.
    echo 🎉 All documentation files built successfully!
    echo 📚 Your Veridot documentation is ready for GitHub Pages!
    echo.
    echo Next steps:
    echo 1. Commit all changes to your repository
    echo 2. Push to GitHub
    echo 3. Enable GitHub Pages in repository settings
    echo 4. Select 'GitHub Actions' as the source
    echo.
    echo Your documentation will be available at:
    echo https://[username].github.io/veridot/
    echo.
    
    REM Optionally serve locally
    set /p "serve=🌐 Would you like to serve the site locally for testing? (y/n): "
    if /i "%serve%"=="y" (
        echo 🌍 Starting local server at http://localhost:4000
        echo Press Ctrl+C to stop the server
        call bundle exec jekyll serve --host 0.0.0.0 --port 4000
    )
) else (
    echo ❌ Some files are missing. Please check the build process.
    pause
    exit /b 1
)

pause