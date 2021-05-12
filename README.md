# uriel

               The Uriel Project
               
Uriel, for this project, stands for Uniform Resource Identifier Expression Language. Uriel is also the name of an angel that brings the wisdom and light of God to humanity. 

The Uriel project aims for several goals, all revolving around expressing web resources management with URIs and REST methods. The first targets to be achieve would be:
- scriting language to download, upload, create, update and destroy web resources
- generate REST APIs services for database schemas and data
- natural language database query
- vocally driven resource management

The language syntax itself is based on URI tokens and HTTP verbs, with added functions for data types, comparison, logic, math and flow control. It is a mix of prefix and infix syntax where some functions can be used like operators but all of them can be used with a prefix syntax. There is no punctuation symbols (dot, comma, parenthesis, ...) aside from the double quote string delimiter. The end of line is the only expression delimiter. There will be some operator precedence but it is not well defined yet. This is a work in progress that should evolve in a way to keep the language syntax close to a natural language feeling. For now, if several functions are present on a single line, the parser will create sub-expressions for each function after the first one, based on parameters requirements.

To build the application: ./build.sh

To build a native version: ./build.sh native

To run the REPL: ./run.sh

To create and run a new service: ./run.sh service "config filename" create build run

See examples folder for more details

*** WARNING: the code still has some bugs so things might not work as expected ***

Here is an example of what a running Uriel REPL session could look like:
```
   ? get x
   = null
   ? put x 1
   = 1
   ? get x
   = 1
   ? put y 2
   = 2
   ? put z add x y
   = 3.0
   ? equal z 3
   = true
   ? get http://magicreg.com/
   = <html> ... </html>
   ? put file:some-file.txt "some text to save"
   = file:some-file.txt
   ? get file:some-file.txt
   = some text to save
   ? put a list 1 true "hello"
   = 1 true hello
   ? put b list 1 true hello x y z
   = 1 true null 1 2 3
   ? post a 42
   = 42
   ? get a
   = 1 true hello 42
   ? delete a/2
   = true
   ? delete a/5
   = false
   ? get a
   = 1 true 42
```

I hope you get the idea ...



