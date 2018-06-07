# Selenium

The selenium plugin provides a few methods but the most important one is: ``selenium.selenese()``

If given no parameters, the method will pick up a resource by the name ``testcase.html``. You can also give a custom resource name or pass in string, for example:

```python
selenese("mytestcase.html")
```

This file should contain the HTML content created by the selenium IDE.

The plugin interprets the html file and maps the commands to the webdriver interface. Some things to note:

- Not all the commands are as of yet mapped, this is an ongoing process
- Verify/assert commands are mapped to the validate/confirm logic in glue
- You can capture variables in the pipeline using the store functionality
- The "echo" method is abused to allow you to set a comment on the next validate/confirm statement

Additional (optional) arguments are browser and language e.g.:

```python
selenese("mytestcase.html", "chrome", "nl")
```

Would start the testcase in the chrome browser using the language "nl".

## Selenium Utilities

When creating selenium tests, you often have repeating parts, the most obvious example is a login sequence. You can centralize these repeated actions into reusable scripts, for example at the top level you could do this:

```python
browser = webdriver()
seleneseLogin("user", "pass", browser)
selenese("doSomething.html", browser)
close(browser)
```

The script ``seleneseLogin`` would look like this:

```python
browser ?= null
selenese("login.html", browser)
```

You can chain such utility scripts together to get to the actual thing you want to test.

## Selenium Actions

All implemented selenium methods are also available for a direct call by using the ``selenium`` namespace, for example:

```python
browser = webdriver()
selenium.click(browser, "/path/to/element")
selenium.waitForTextPresent(browser, "/path/to/element", "textToCheck")
```

## Screenshots

Screenshots are supported by using the ``captureEntirePageScreenshot`` command.

All screenshots are stored centrally in the context of the script run under the variable ``screenshots``. Additionally all screenshots in a selenium script are injected into the pipeline using a increasing number, the first screenshot will be called ``$screenshot0``, the second ``$screenshot1`` etc.

## Properties

All properties can be set either in the ``.glue`` file, as a system property using the ``-D`` notation or as an environment variable on the system.

You can set the default browser to use globally by setting the property ``selenium.browser``, e.g. in the ``.glue`` file put:

```
*.selenium.browser = chrome
```

You can set the default language to use globally by setting the property ``selenium.language``, e.g. in the file ``.glue`` put:

```
*.selenium.language = nl
```

You can perform the execution [remotely](http://docs.seleniumhq.org/docs/07_selenium_grid.jsp) by setting the property ``selenium.server.url``, e.g. in the file ``.glue`` put:

```
*.selenium.server.url = http://myserver:4444/wd/hub
```

## Browser support

### Firefox

You can explicitly force the use of firefox by specifying ``firefox`` as the browser.

For firefox you need to download the [gecko driver](https://github.com/mozilla/geckodriver/releases) and configure a system property ``webdriver.gecko.driver`` that points to it.

### Chrome

You can explicitly force the use of [chrome/chromium](https://sites.google.com/a/chromium.org/chromedriver/) by specifying ``chrome`` or ``chromium`` as the browser.

Both chrome and chromium are supported but you need to [install](https://sites.google.com/a/chromium.org/chromedriver/getting-started) the correct chrome driver for your system. On debian-based systems you can simply run:

```
$ sudo apt-get install chromium-browser chromium-chromedriver
```

Next you need to tell the code where the chromium driver is installed, e.g. on the same debian-based system, we need the property ``webdriver.chrome.driver``, e.g. in the file ``.glue`` put:

```
*.webdriver.chrome.driver=/usr/lib/chromium-browser/chromedriver
```

**Note**: currently the language setting does not appear to be working for the chromium setup, as yet untested with chrome. You can always manually set the language in the target browser.

### Internet Explorer

You can explicitly force the use of [internet explorer](https://code.google.com/p/selenium/wiki/InternetExplorerDriver) by specifying ``explorer`` or ``internet explorer`` as the browser.

**Note**: there appears to be no way to set the language programmatically so this setting is ignored for internet explorer. Instead you need to configure the language on the target system.

### Opera

You can explicitly force the use of [opera](https://code.google.com/p/selenium/wiki/OperaDriver) by specifying ``opera`` as the browser.

**Note**: currently only the new blink-based opera is supported, this is as yet untested. No language support currently.

### Safari

You can explicitly force the use of [safari](https://code.google.com/p/selenium/wiki/SafariDriver) ``safari`` as the browser.

**Note**: this is as yet untested. No language support currently.
