// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package output

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"text/tabwriter"
)

// PrintTable formats and prints data as an ASCII table to stdout.
// Headers define the column names, and rows contain the table data.
// The table uses tab-separated values with 2-space column padding.
func PrintTable(headers []string, rows [][]string) {
	// Create a new tabwriter with custom parameters
	// minwidth=0, tabwidth=1, padding=2, padchar=' ', flags=0
	w := tabwriter.NewWriter(os.Stdout, 0, 1, 2, ' ', 0)

	// Write header row
	headerRow := ""
	for i, header := range headers {
		if i > 0 {
			headerRow += "\t"
		}
		headerRow += header
	}
	fmt.Fprintln(w, headerRow)

	// Write data rows
	for _, row := range rows {
		dataRow := ""
		for i, cell := range row {
			if i > 0 {
				dataRow += "\t"
			}
			dataRow += cell
		}
		fmt.Fprintln(w, dataRow)
	}

	// Flush the tabwriter to output the formatted table
	w.Flush()
}

// PrintJSON formats and prints data as JSON to stdout.
// Data is marshalled with 2-space indentation for readability.
func PrintJSON(data interface{}) {
	printJSONToWriter(os.Stdout, data)
}

// printJSONToWriter is a helper function that encodes data as JSON to a given writer.
// This allows for easier testing of error conditions.
func printJSONToWriter(w io.Writer, data interface{}) {
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(data); err != nil {
		fmt.Fprintf(os.Stderr, "Error encoding JSON: %v\n", err)
	}
}

// PrintError prints an error message to stderr.
// The message is prefixed with "Error: " for clarity.
func PrintError(message string) {
	fmt.Fprintf(os.Stderr, "Error: %s\n", message)
}
