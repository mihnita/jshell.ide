# Create a jshell REPL IDE / application

The default jskell REPL is very unfriendly.
It is text based, has only primitive auto-completion or syntax highlighting, the key bindings cannot be changed.

The job is to create a jshell that is more IDE-like.
GUI, with menus for the main commands, remapping key bindings, syntax highlighting.
Two panels, one for editing and one with live output of executing what is in the editor.
In the editor auto-completion, squiggles for incorrect code.

A easy way to specify additional class-paths, including a list of maven dependencies that can be downloaded if needed.

I recommend using maven and the Eclipse SWT components.
It is fine to use other dependencies as needed.

Use the functionality exposed by jdk.jshell.
