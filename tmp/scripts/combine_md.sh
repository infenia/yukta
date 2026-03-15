#!/bin/bash

OUTPUT_FILE="combined_output.md"
> "$OUTPUT_FILE"

if [ $# -eq 0 ]; then
    echo "Usage: $0 <dir1> [dir2...]"
    exit 1
fi

for dir in "$@"; do
    # Remove trailing slash for cleaner path substitution
    dir_cleaned="${dir%/}"

    if [ -d "$dir_cleaned" ]; then
        echo "Processing directory: $dir_cleaned"

        find "$dir_cleaned" -type f ! -name "$OUTPUT_FILE" | while read -r file; do

            # Get just the filename and the path relative to the input dir
            filename=$(basename "$file")
            relative_path="${file#$dir_cleaned/}"

            echo "Adding: $relative_path"

            # Write to Markdown
            echo "---" >> "$OUTPUT_FILE"
            echo "### $filename" >> "$OUTPUT_FILE"
            echo "Location: \`$relative_path\`" >> "$OUTPUT_FILE"
            echo "" >> "$OUTPUT_FILE"

            echo '```' >> "$OUTPUT_FILE"
            cat "$file" >> "$OUTPUT_FILE"
            # Ensure there is a newline before closing the code block
            echo -e "\n" '```' >> "$OUTPUT_FILE"
            echo "" >> "$OUTPUT_FILE"

        done
    else
        echo "Error: $dir_cleaned not found."
    fi
done

echo "Success! Content merged into $OUTPUT_FILE"