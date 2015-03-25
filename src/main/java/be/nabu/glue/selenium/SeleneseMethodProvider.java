package be.nabu.glue.selenium;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.opera.OperaDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import be.nabu.glue.ScriptRuntime;
import be.nabu.glue.api.ExecutionContext;
import be.nabu.glue.api.MethodDescription;
import be.nabu.glue.api.MethodProvider;
import be.nabu.glue.api.ParameterDescription;
import be.nabu.glue.impl.SimpleMethodDescription;
import be.nabu.glue.impl.SimpleParameterDescription;
import be.nabu.glue.impl.methods.ScriptMethods;
import be.nabu.glue.impl.methods.TestMethods;
import be.nabu.libs.evaluator.EvaluationException;
import be.nabu.libs.evaluator.QueryPart;
import be.nabu.libs.evaluator.QueryPart.Type;
import be.nabu.libs.evaluator.api.Operation;
import be.nabu.libs.evaluator.api.OperationProvider.OperationType;
import be.nabu.libs.evaluator.base.BaseOperation;

/**
 * To run it remotely, I first tried the root URL: http://my-server:4444
 * However this gave a vague classcastexception that it can not cast a string to a map on this line in remote webdriver (242):
 * 
 *      Map<String, Object> rawCapabilities = (Map<String, Object>) response.getValue();
 *      
 * In trace mode you can see that the response is actually this: 
 *     (Response: SessionID: null, Status: 0, Value: <html><head><title>Selenium Grid2.0 help</title></head><body>You are using grid 2.43.1<br>Find help on the official selenium wiki : <a href='http://code.google.com/p/selenium/wiki/Grid2' >more help here</a><br>default monitoring page : <a href='/grid/console' >console</a></body></html>)
 * 
 * What fixed it was adding "wd/hub" to the URL (found this on a stackoverflow):
 * 			http://my-server:4444/wd/hub
 * 
 * It takes a while to do the initial connect but after that it's fast.
 */
public class SeleneseMethodProvider implements MethodProvider {
	
	@Override
	public Operation<ExecutionContext> resolve(String name) {
		if (name.equalsIgnoreCase("selenese")) {
			return new SeleneseOperation();
		}
		return null;
	}
	
	public static void transform(InputStream xml, InputStream xsl, OutputStream result) throws TransformerFactoryConfigurationError, TransformerException {
		Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(xsl));
		transformer.transform(new StreamSource(xml), new StreamResult(result));
	}
	
	public static class SeleneseOperation extends BaseOperation<ExecutionContext> {

		private long sleep = 10000, maxWait;
		
		private List<File> temporaryFiles = new ArrayList<File>();
		
		@Override
		public void finish() throws ParseException {
			// do nothing
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			// first the method name
			builder.append((String) getParts().get(0).getContent());
			// then the rest
			builder.append("(");
			for (int i = 1; i < getParts().size(); i++) {
				QueryPart part = getParts().get(i);
				if (i > 1) {
					builder.append(", ");
				}
				if (part.getType() == Type.STRING) {
					builder.append("\"" + part.getContent().toString() + "\"");
				}
				else {
					builder.append(part.getContent() == null ? "null" : part.getContent().toString());
				}
			}
			builder.append(")");
			return builder.toString();
		}
		
		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public Object evaluate(ExecutionContext context) throws EvaluationException {
			List arguments = new ArrayList();
			for (int i = 1; i < getParts().size(); i++) {
				Operation<ExecutionContext> argumentOperation = (Operation<ExecutionContext>) getParts().get(i).getContent();
				arguments.add(argumentOperation.evaluate(context));
			}
			if (arguments.size() == 0) {
				arguments.add("testcase.html");
			}
			InputStream xml = null;
			if (arguments.get(0) instanceof String) {
				xml = find((String) arguments.get(0));
			}
			else if (arguments.get(0) instanceof byte[]) {
				xml = new ByteArrayInputStream((byte []) arguments.get(0));
			}
			else if (arguments.get(0) instanceof InputStream) {
				xml = (InputStream) arguments.get(0);
			}
			else {
				throw new EvaluationException("Can not process " + arguments.get(0));
			}
			try {
				SeleneseTestCase testCase = parse(xml, context);
				String language = arguments.size() >= 2 && arguments.get(1) != null ? arguments.get(1).toString() : ScriptMethods.environment("selenium.language");
				String browser = arguments.size() >= 3 && arguments.get(2) != null ? arguments.get(2).toString() : ScriptMethods.environment("selenium.browser");
				run(getDriver(browser, language), testCase, context);
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
			for (File temporaryFile : temporaryFiles) {
				temporaryFile.delete();
			}
			return true;
		}
		
		private DesiredCapabilities getSafariCapabilities(String language) {
			DesiredCapabilities capabilities = DesiredCapabilities.safari();
			return capabilities;
		}
		
		private DesiredCapabilities getOperaCapabilities(String language) {
			DesiredCapabilities capabilities = DesiredCapabilities.operaBlink();
			return capabilities;
		}
		
		private DesiredCapabilities getFirefoxCapabilities(String language) {
			DesiredCapabilities capabilities = DesiredCapabilities.firefox();
			FirefoxProfile profile = new FirefoxProfile();
			if (language != null) {
				profile.setPreference("intl.accept_languages", language);
			}
			capabilities.setCapability(FirefoxDriver.PROFILE, profile);
			return capabilities;
		}
		
		private WebDriver getFirefoxDriver(String language) {
			return new FirefoxDriver(getFirefoxCapabilities(language));
		}
		
		private WebDriver getSafariDriver(String language) {
			return new SafariDriver(getSafariCapabilities(language));
		}
		
		private WebDriver getOperaDriver(String language) {
			return new OperaDriver(getOperaCapabilities(language));
		}
		
		private WebDriver getChromeDriver(String language) {
			// if the system property is not set but you have it configured in the glue properties, push it to the system properties
			if (System.getProperty("webdriver.chrome.driver") == null && ScriptMethods.environment("webdriver.chrome.driver") != null) {
				System.setProperty("webdriver.chrome.driver", ScriptMethods.environment("webdriver.chrome.driver"));
			}
			return new ChromeDriver(getChromeCapabilities(language));
		}
		
		private WebDriver getInternetExplorerDriver(String language) {
			return new InternetExplorerDriver(getInternetExplorerCapabilities(language));
		}

		private DesiredCapabilities getInternetExplorerCapabilities(String language) {
			DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();
			return capabilities;
		}
		
		private DesiredCapabilities getChromeCapabilities(String language) {
			DesiredCapabilities capabilities = DesiredCapabilities.chrome();
			ChromeOptions co = new ChromeOptions();
			if (language != null) {
				co.addArguments("--lang=" + language);
			}
			capabilities.setCapability(ChromeOptions.CAPABILITY, co);
			return capabilities;
		}
		
		protected WebDriver getRemoteDriver(URL url, DesiredCapabilities capabilities) {
			return new RemoteWebDriver(url, capabilities);
		}
		
		private WebDriver getDriver(String browser, String language) throws MalformedURLException {
			String url = ScriptMethods.environment("selenium.server.url");
			// local execution
			if (url == null) {
				if (browser != null && (browser.equalsIgnoreCase("chrome") || browser.equalsIgnoreCase("chromium"))) {
					return getChromeDriver(language);
				}
				else if (browser != null && (browser.equalsIgnoreCase("ie") || browser.equalsIgnoreCase("explorer") || browser.equals("internet explorer"))) {
					return getInternetExplorerDriver(language);
				}
				else if (browser != null && browser.equalsIgnoreCase("opera")) {
					return getOperaDriver(language);
				}
				else if (browser != null && browser.equalsIgnoreCase("safari")) {
					return getSafariDriver(language);
				}
				else {
					return getFirefoxDriver(language);
				}
			}
			else {
				if (browser != null && (browser.equalsIgnoreCase("chrome") || browser.equalsIgnoreCase("chromium"))) {
					return getRemoteDriver(new URL(url), getChromeCapabilities(language));
				}
				else if (browser != null && (browser.equalsIgnoreCase("ie") || browser.equalsIgnoreCase("explorer") || browser.equals("internet explorer"))) {
					return getRemoteDriver(new URL(url), getInternetExplorerCapabilities(language));
				}
				else if (browser != null && browser.equalsIgnoreCase("opera")) {
					return getRemoteDriver(new URL(url), getOperaCapabilities(language));
				}
				else if (browser != null && browser.equalsIgnoreCase("safari")) {
					return getRemoteDriver(new URL(url), getSafariCapabilities(language));
				}
				else {
					return getRemoteDriver(new URL(url), getFirefoxCapabilities(language));
				}
			}
		}
		
		@SuppressWarnings("unchecked")
		private void run(WebDriver driver, SeleneseTestCase testCase, ExecutionContext context) throws EvaluationException, IOException {
			maxWait = ScriptMethods.environment("selenium.server.timeout") == null ? 60000 : new Long(ScriptMethods.environment("selenium.server.url"));
			String baseURL = testCase.getTarget().replaceAll("[/]+$", "");
			SeleneseStep previousStep = null;
			boolean closed = false;
			try {
				String lastComment = null;
				for (SeleneseStep step : testCase.getSteps()) {
					if (ScriptRuntime.getRuntime().isAborted()) {
						break;
					}
					String message = step.getAction();
					if (step.getTarget() != null && !step.getTarget().isEmpty()) {
						message += " @ " + step.getTarget();
					}
					if (step.getContent() != null && !step.getContent().isEmpty()) {
						message += ": " + step.getContent();
					}
					if (ScriptRuntime.getRuntime().getExecutionContext().isDebug()) {
						ScriptRuntime.getRuntime().getFormatter().print(message);
					}
					// the variables must be defined by now
					if (step.getAction() != null) {
						step.setAction(ScriptRuntime.getRuntime().getScript().getParser().substitute(step.getAction(), context, false));
					}
					if (step.getTarget() != null) {
						step.setTarget(ScriptRuntime.getRuntime().getScript().getParser().substitute(step.getTarget(), context, false));
					}
					if (step.getContent() != null) {
						step.setContent(ScriptRuntime.getRuntime().getScript().getParser().substitute(step.getContent(), context, false));
					}
					if (step.getAction().equalsIgnoreCase("open")) {
						driver.get(step.getTarget().matches("^http[s]*://.*") ? step.getTarget() : baseURL + step.getTarget());
					}
					else if (step.getAction().equalsIgnoreCase("echo")) {
						lastComment = step.getTarget();
					}
					else if (step.getAction().equalsIgnoreCase("waitForTextPresent")) {
						Date date = new Date();
						while (!Thread.interrupted()) {
							if (driver.getPageSource().contains(step.getTarget())) {
								break;
							}
							else if (new Date().getTime() - date.getTime() > maxWait) {
								throw new EvaluationException("Timed out waiting for: " + step.getTarget());
							}
						}
					}
					else if (step.getAction().equalsIgnoreCase("waitForTextNotPresent")) {
						Date date = new Date();
						while (!Thread.interrupted()) {
							if (!driver.getPageSource().contains(step.getTarget())) {
								break;
							}
							else if (new Date().getTime() - date.getTime() > maxWait) {
								throw new EvaluationException("Timed out waiting for: " + step.getTarget());
							}
						}
					}
					else if (step.getAction().equalsIgnoreCase("verifyTextPresent") || step.getAction().equalsIgnoreCase("assertTextPresent")) {
						boolean fail = step.getAction().startsWith("assert");
						String validateMessage = lastComment == null ? "Verify presence of text" : lastComment;
						lastComment = null;
						if (step.getTarget().contains("*")) {
							TestMethods.check(validateMessage, driver.getPageSource().matches(".*" + step.getTarget().replaceAll("\\*", ".*") + ".*"), step.getTarget(), fail);
						}
						else {
							TestMethods.check(validateMessage,  driver.getPageSource().contains(step.getTarget()), step.getTarget(), fail);
						}
					}
					else if ((step.getContent() == null || step.getContent().isEmpty()) && (step.getAction().equalsIgnoreCase("verifyTextNotPresent") || step.getAction().equalsIgnoreCase("assertTextNotPresent"))) {
						String validateMessage = lastComment == null ? "Verify absence of text" : lastComment;
						lastComment = null;
						TestMethods.check(validateMessage, !driver.getPageSource().contains(step.getTarget()), step.getTarget(), step.getAction().startsWith("assert"));
					}
					else if (step.getAction().equalsIgnoreCase("windowMaximize")) {
						driver.manage().window().maximize();
					}
					else if (step.getAction().equalsIgnoreCase("waitForPageToLoad")) {
						// TODO
					}
					else if (step.getAction().equalsIgnoreCase("waitForFrameToLoad")) {
						// TODO
					}
					else if (step.getAction().equalsIgnoreCase("setSpeed")) {
						this.sleep = new Long(step.getTarget()) * 1000;
					}
					else if (step.getAction().equalsIgnoreCase("close")) {
						driver.close();
						closed = true;
						break;
					}
					else if (step.getAction().equalsIgnoreCase("waitForPopUp")) {
						// do nothing
					}
					else if (step.getAction().equalsIgnoreCase("captureEntirePageScreenshot")) {
						String name = step.getTarget() != null && !step.getTarget().isEmpty() ? step.getTarget().replace('\\', '/').replaceAll(".*/", "") : UUID.randomUUID().toString();
						byte [] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
						// if you are debugging, write the screenshots to file
						if (ScriptRuntime.getRuntime().getExecutionContext().isDebug()) {
							File temporary = File.createTempFile("screenshot_" + name, ".png");
							FileOutputStream output = new FileOutputStream(temporary);
							try {
								output.write(screenshot);
								ScriptRuntime.getRuntime().getFormatter().print("Created screenshot " + temporary);
							}
							finally {
								output.close();
							}
						}
						if (!ScriptRuntime.getRuntime().getContext().containsKey("screenshots")) {
							ScriptRuntime.getRuntime().getContext().put("screenshots", new LinkedHashMap<String, byte[]>());
						}
						Map<String, byte[]> screenshots = (Map<String, byte[]>) ScriptRuntime.getRuntime().getContext().get("screenshots");
						screenshots.put(name.endsWith(".png") ? name : name + ".png", screenshot);
					}
					else if (step.getAction().equalsIgnoreCase("selectWindow")) {
						if (previousStep != null && previousStep.getAction().equalsIgnoreCase("waitForPopup")) {
							WebDriverWait wait = new WebDriverWait(driver, this.sleep / 1000);
							wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(step.getTarget()));
						}
						else {
							driver.switchTo().window(step.getTarget().equals("null") ? null : step.getTarget());
						}
					}
					else if (step.getAction().equalsIgnoreCase("assertConfirmation")) {
						Alert alert = driver.switchTo().alert();
						if (alert.getText().equals(step.getTarget())) {
							alert.accept();
						}
					}
					else {
						String attribute = null;
						By by = null;
						if (step.getTarget().matches("^[\\w]+=.*")) {
							int index = step.getTarget().indexOf('=');
							String selector = step.getTarget().substring(0, index).trim();
							String value = step.getTarget().substring(index + 1).trim();
							if (selector.equalsIgnoreCase("id")) {
								by = By.id(value);
							}
							else if (selector.equalsIgnoreCase("css")) {
								by = By.cssSelector(value);
							}
							else if (selector.equalsIgnoreCase("link")) {
								by = By.linkText(value);
							}
							else if (selector.equalsIgnoreCase("xpath")) {
								if (step.getAction().equalsIgnoreCase("verifyAttribute") || step.getAction().equalsIgnoreCase("assertAttribute")) {
									int attributeLocation = value.lastIndexOf('@');
									if (attributeLocation >= 0) {
										attribute = value.substring(attributeLocation + 1);
										value = value.substring(0, attributeLocation);
									}
								}
								by = By.xpath(value);
							}
							// TODO: the "class" selector is a guess, i haven't actually seen it in action yet
							else if (selector.equalsIgnoreCase("class")) {
								by = By.className(value);
							}
							// TODO
							else if (selector.equalsIgnoreCase("tag")) {
								by = By.tagName(value);
							}
							// TODO
							else if (selector.equalsIgnoreCase("name")) {
								by = By.name(value);
							}
							else {
								throw new EvaluationException("Unknown selector type: " + selector);
							}
						}
						else {
							by = By.xpath(step.getTarget());
						}
						
						ExpectedCondition<?> presenceOfElementLocated;
						
						if (step.getAction().equalsIgnoreCase("waitForNotText")) {
							presenceOfElementLocated = ExpectedConditions.not(ExpectedConditions.presenceOfElementLocated(by));
						}
						else {
							presenceOfElementLocated = ExpectedConditions.presenceOfElementLocated(by);
						}
						
						WebDriverWait wait = new WebDriverWait(driver, this.sleep / 1000);
						wait.until(presenceOfElementLocated);
						
						WebElement element = driver.findElement(by);
						// you are requesting a file upload
						if (element == null) {
							throw new EvaluationException("Can not find element " + by);
						}
						if (step.getAction().equalsIgnoreCase("type")) {
							if (element.getTagName().equalsIgnoreCase("input") && element.getAttribute("type").equalsIgnoreCase("file")) {
								String fileName = step.getContent().replaceAll(".*[\\\\/]+([^\\\\/]+)$", "$1");
								InputStream content = find(fileName);
								if (content == null) {
									throw new FileNotFoundException("Could not find file " + fileName + " for upload");
								}
								try {
									File file = File.createTempFile(fileName.replaceAll("\\.[^.]+", ""), fileName.replaceAll(".*\\.([^.]+)", "$1"));
									temporaryFiles.add(file);
									FileOutputStream output = new FileOutputStream(file);
									try {
										int read = 0;
										byte [] buffer = new byte[4096];
										while ((read = content.read(buffer)) != -1) {
											output.write(buffer, 0, read);
										}
										element.sendKeys(file.getAbsolutePath());
									}
									finally {
										output.close();
									}
								}
								finally {
									content.close();
								}
							}
							else {
								element.sendKeys(step.getContent());
							}
						}
						else if (step.getAction().equalsIgnoreCase("storeText")) {
							context.getPipeline().put(step.getContent(), element.getText());
						}
						else if (step.getAction().equalsIgnoreCase("doubleClick")) {
							Actions action = new Actions(driver);
							// there is apparently a bug where you have to send this before the double click will work: http://stackoverflow.com/questions/25756876/selenium-webdriver-double-click-doesnt-work
							driver.findElement(by).sendKeys("");
							action.doubleClick(element).perform();
						}
						else if (step.getAction().equalsIgnoreCase("waitForElementPresent") || step.getAction().equalsIgnoreCase("verifyElementPresent")) {
							// we already waited for the element, so keep going
						}
						else if (step.getAction().equalsIgnoreCase("verifyText") || step.getAction().equalsIgnoreCase("assertText") || step.getAction().equalsIgnoreCase("verifyAttribute") || step.getAction().equalsIgnoreCase("assertAttribute") || step.getAction().equalsIgnoreCase("waitForText")) {
							boolean fail = step.getAction().startsWith("assert");
							String type = step.getAction().replaceAll("^(wait|verify|assert)", "").toLowerCase();
							String text = attribute == null ? element.getText() : element.getAttribute(attribute);
							String validateMessage = lastComment == null ? "Verify presence of " + type + " in " + step.getTarget() : lastComment;
							lastComment = null;
							if (step.getTarget().contains("*")) {
								boolean matches = text.matches(".*" + step.getContent().replaceAll("\\*", ".*") + ".*");
								TestMethods.check(validateMessage, matches, matches ? step.getContent() : text + " !~ " + step.getContent(), fail);
							}
							else {
								boolean contains = text.contains(step.getContent());
								TestMethods.check(validateMessage, contains, contains ? step.getContent() : text + " !# " + step.getContent(), fail);
							}
						}
						else if (step.getAction().equalsIgnoreCase("verifyNotText") || step.getAction().equalsIgnoreCase("assertNotText") || step.getAction().equalsIgnoreCase("verifyNotAttribute") || step.getAction().equalsIgnoreCase("assertNotAttribute")) {
							String type = step.getAction().replaceAll("^(verify|assert)Not", "").toLowerCase();
							String validateMessage = lastComment == null ? "Verify presence of " + type + " in " + step.getTarget() : lastComment;
							lastComment = null;
							TestMethods.check(validateMessage, !element.getText().contains(step.getContent()), step.getContent(), step.getAction().startsWith("assert"));
						}
						else if (step.getAction().equalsIgnoreCase("clickAndWait")) {
							element.click();
							try {
								Thread.sleep(step.getContent() == null || step.getContent().isEmpty() ? this.sleep : new Long(step.getContent()));
							}
							catch (InterruptedException e) {
								// continue
							}
						}
						else if (step.getAction().equalsIgnoreCase("click") || (step.getAction().equalsIgnoreCase("clickAt") && (step.getContent() == null || step.getContent().trim().isEmpty()))) {
							element.click();
						}
						else if (step.getAction().equalsIgnoreCase("clickAt")) {
							Actions action = new Actions(driver);
							String [] parts = step.getContent().split(",");
							action.moveToElement(element, new Integer(parts[0]), parts.length > 1 ? new Integer(parts[1]) : 0).click().perform();
						}
					}
					previousStep = step;
				}
			}
			finally {
				if (!closed) {
					driver.close();
				}
			}
		}

		private InputStream find(String name) throws EvaluationException {
			ScriptRuntime runtime = ScriptRuntime.getRuntime();
			InputStream xml = null;
			try {
				while (runtime != null) {
					xml = ScriptRuntime.getRuntime().getExecutionContext().getContent(name);
					if (xml != null) {
						break;
					}
					runtime = runtime.getParent();
				}
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
			if (xml == null) {
				throw new EvaluationException("Can not find the xml " + name);
			}
			return xml;
		}
		
		private SeleneseTestCase parse(InputStream xml, ExecutionContext context) throws EvaluationException, IOException {
			try {
				Charset charset = ScriptRuntime.getRuntime().getScript().getCharset();
				InputStream xsl = getClass().getClassLoader().getResourceAsStream("selenese2xml.xslt");
				// remove the doctype in the header, it crashes some code
				xml = new ByteArrayInputStream(new String(ScriptMethods.bytes(xml), charset).replaceFirst("<!DOCTYPE[^>]+>", "").getBytes(charset));
				if (xsl == null) {
					throw new RuntimeException("Can not find the file selenese2xml.xslt");
				}
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				transform(xml, xsl, output);
				String string = new String(output.toByteArray(), charset);
				// in selenium you can make use of variables while running, so allow null for now
				string = ScriptRuntime.getRuntime().getScript().getParser().substitute(string, context, true);
				JAXBContext jaxb = JAXBContext.newInstance(SeleneseTestCase.class);
				return (SeleneseTestCase) jaxb.createUnmarshaller().unmarshal(new StreamSource(new ByteArrayInputStream(string.getBytes(charset))));
			}
			catch (TransformerFactoryConfigurationError e) {
				throw new EvaluationException(e);
			}
			catch (TransformerException e) {
				throw new EvaluationException(e);
			}
			catch (JAXBException e) {
				throw new EvaluationException(e);
			}
		}

		@Override
		public OperationType getType() {
			return OperationType.METHOD;
		}
	}
	
	@XmlRootElement(name = "testCase", namespace = "http://nabu.be/glue/selenese")
	public static class SeleneseTestCase {
		private List<SeleneseStep> steps;
		private String target;

		@XmlElement(name = "step", namespace = "http://nabu.be/glue/selenese")
		public List<SeleneseStep> getSteps() {
			return steps;
		}

		public void setSteps(List<SeleneseStep> steps) {
			this.steps = steps;
		}

		@XmlAttribute
		public String getTarget() {
			return target;
		}
		public void setTarget(String target) {
			this.target = target;
		}
	}
	
	public static class SeleneseStep {
		private String action, target, content;

		@XmlAttribute
		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		@XmlAttribute
		public String getTarget() {
			return target;
		}

		public void setTarget(String target) {
			this.target = target;
		}

		@XmlValue
		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}
		
	}

	@Override
	public List<MethodDescription> getAvailableMethods() {
		List<MethodDescription> descriptions = new ArrayList<MethodDescription>();
		descriptions.add(new SimpleMethodDescription("selenium", "selenese", "This will run a selenese script created using the selenium IDE",
			Arrays.asList(new ParameterDescription [] { new SimpleParameterDescription("script", "You can pass in the name of the file that holds the selense or alternatively you can pass in byte[] or InputStream", "String, byte[], InputStream") } ),
			new ArrayList<ParameterDescription>()));
		return descriptions;
	}
}
