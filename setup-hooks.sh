#!/bin/bash
echo "Setting up Git hooks..."

# Configure Git to use the .githooks directory for hooks
git config core.hooksPath .githooks

echo "Git hooks set up successfully!"