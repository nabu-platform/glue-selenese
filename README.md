Adds a method `selenese()` that can take a HTML file created by the selenium IDE.

Note:

- The HTML file is **interpreted** and mapped to the webdriver API at runtime.
- It integrates with the existing validation system.
- By default it will use a local webdriver but setting the system property `selenium.server.url` will switch to a remote webdriver
- Not all commands are currently interpreted, the codebase is expanded on an as-needed basis
- The `echo` command is "abused" in that it allows you to set the comment on the next validation instead of it being outputted directly
