#!/bin/bash

# Veridot Documentation Build and Test Script
# This script tests the Jekyll build locally before GitHub Pages deployment

echo "🚀 Testing Veridot Documentation Build..."

# Check if Jekyll is installed
if ! command -v jekyll &> /dev/null; then
    echo "❌ Jekyll not found. Installing..."
    gem install jekyll bundler
fi

# Install dependencies
echo "📦 Installing dependencies..."
bundle install

# Build the site
echo "🔨 Building Jekyll site..."
bundle exec jekyll build

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    
    # Check for required files
    echo "🔍 Verifying documentation files..."
    
    required_files=(
        "_site/index.html"
        "_site/docs/getting-started.html"
        "_site/docs/java-guide.html"
        "_site/docs/nodejs-guide.html"
        "_site/docs/api-reference.html"
        "_site/docs/security.html"
        "_site/assets/main.css"
    )
    
    all_files_exist=true
    for file in "${required_files[@]}"; do
        if [ -f "$file" ]; then
            echo "✅ Found: $file"
        else
            echo "❌ Missing: $file"
            all_files_exist=false
        fi
    done
    
    if [ "$all_files_exist" = true ]; then
        echo ""
        echo "🎉 All documentation files built successfully!"
        echo "📚 Your Veridot documentation is ready for GitHub Pages!"
        echo ""
        echo "Next steps:"
        echo "1. Commit all changes to your repository"
        echo "2. Push to GitHub"
        echo "3. Enable GitHub Pages in repository settings"
        echo "4. Select 'GitHub Actions' as the source"
        echo ""
        echo "Your documentation will be available at:"
        echo "https://[username].github.io/veridot/"
        
        # Optionally serve locally
        read -p "🌐 Would you like to serve the site locally for testing? (y/n): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo "🌍 Starting local server at http://localhost:4000"
            bundle exec jekyll serve --host 0.0.0.0 --port 4000
        fi
    else
        echo "❌ Some files are missing. Please check the build process."
        exit 1
    fi
else
    echo "❌ Build failed. Please check the Jekyll configuration."
    exit 1
fi