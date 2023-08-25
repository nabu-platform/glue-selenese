package be.nabu.glue.selenium;

import java.io.BufferedInputStream;

//import io.github.bonigarcia.wdm.WebDriverManager;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v114.page.Page;
import org.openqa.selenium.devtools.v114.page.Page.StartScreencastFormat;
import org.openqa.selenium.devtools.v114.page.model.ScreencastFrame;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.interactions.Actions;
//import org.openqa.selenium.opera.OperaDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariOptions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.glue.api.ExecutionContext;
import be.nabu.glue.api.MethodDescription;
import be.nabu.glue.api.ParameterDescription;
import be.nabu.glue.core.api.MethodProvider;
import be.nabu.glue.core.impl.methods.FileMethods;
import be.nabu.glue.core.impl.methods.GlueAttachmentImpl;
import be.nabu.glue.core.impl.methods.ScriptMethods;
import be.nabu.glue.core.impl.methods.TestMethods;
import be.nabu.glue.impl.SimpleMethodDescription;
import be.nabu.glue.impl.SimpleParameterDescription;
import be.nabu.glue.utils.ScriptRuntime;
import be.nabu.libs.evaluator.EvaluationException;
import be.nabu.libs.evaluator.annotations.MethodProviderClass;
import be.nabu.libs.evaluator.api.Operation;
import be.nabu.libs.evaluator.base.BaseMethodOperation;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.json.JSONBinding;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.impl.Base64Decoder;
import be.nabu.utils.io.IOUtils;

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
 * Note that the reference documentation is available at http://release.seleniumhq.org/selenium-core/1.0.1/reference.html
 * Interesting link (2023): https://www.selenium.dev/documentation/webdriver/actions_api/mouse/
 */
@MethodProviderClass(namespace = "selenium")
public class SeleneseMethodProvider implements MethodProvider {
	
	private static Logger logger = LoggerFactory.getLogger(SeleneseMethodProvider.class);
	
	private static Boolean screenshotInsteadOfVideo = Boolean.parseBoolean(System.getProperty("selenium.screenshotSteps", "true"));
	private static Boolean screenshotAfter = Boolean.parseBoolean(System.getProperty("selenium.screenshotAfter", "true"));
	
	@Override
	public Operation<ExecutionContext> resolve(String name) {
		if (name.equalsIgnoreCase("selenese") || name.equalsIgnoreCase("selenium.selenese")) {
			return new SeleneseOperation();
		}
		else if (name.equalsIgnoreCase("webdriver") || name.equalsIgnoreCase("selenium.webdriver")) {
			return new WebDriverOperation();
		}
		else if (name.equalsIgnoreCase("recording") || name.equalsIgnoreCase("selenium.recording")) {
			SeleneseStepOperation seleneseStepOperation = new SeleneseStepOperation(name.substring("selenium.".length()));
			seleneseStepOperation.setRecord(false);
			return seleneseStepOperation;
		}
		// perform an action on a driver, e.g. selenium.click(driver, ...)
		else if (name.matches("^selenium\\.[\\w]+$")) {
			return new SeleneseStepOperation(name.substring("selenium.".length()));
		}
		
		return null;
	}
	
	public static class WrappedDriver implements Closeable {
		
		private WebDriver driver;
		private List<Closeable> closeables = new ArrayList<Closeable>();
		
		public WrappedDriver(WebDriver driver, Closeable...closeables) {
			this.driver = driver;
			if (closeables != null) {
				this.closeables.addAll(Arrays.asList(closeables));
			}
		}

		public WebDriver getDriver() {
			return driver;
		}

		@Override
		public void close() {
			try {
				driver.quit();
				driver.close();
			}
			catch (Exception e) {
				// do nothing
			}
			for (Closeable closeable : closeables) {
				try {
					closeable.close();
				}
				catch (Exception e) {
					// do nothing
				}
			}
		}
	}
	
	public static void transform(InputStream xml, InputStream xsl, OutputStream result) throws TransformerFactoryConfigurationError, TransformerException {
		Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(xsl));
		transformer.transform(new StreamSource(xml), new StreamResult(result));
	}
	
	public static class WebDriverOperation extends BaseMethodOperation<ExecutionContext> {

		@Override
		public void finish() throws ParseException {
			// do nothing
		}

		@SuppressWarnings({ "rawtypes", "unchecked", "resource" })
		@Override
		public Object evaluate(ExecutionContext context) throws EvaluationException {
			List arguments = new ArrayList();
			for (int i = 1; i < getParts().size(); i++) {
				Operation<ExecutionContext> argumentOperation = (Operation<ExecutionContext>) getParts().get(i).getContent();
				arguments.add(argumentOperation.evaluate(context));
			}
			String browser = arguments.size() >= 1 && arguments.get(0) != null ? arguments.get(0).toString() : ScriptMethods.environment("selenium.browser");
			String language = arguments.size() >= 2 && arguments.get(1) != null ? arguments.get(1).toString() : ScriptMethods.environment("selenium.language");
			String url = arguments.size() >= 3 && arguments.get(2) != null ? arguments.get(2).toString() : ScriptMethods.environment("selenium.server.url");
			
			boolean headless = false;
			if (browser.endsWith("-headless")) {
				headless = true;
				browser = browser.substring(0, browser.length() - "-headless".length());
			}
			try {
				WrappedDriver wrappedDriver = new WrappedDriver(getDriver(browser, language, url, headless));
				Closeable recordSession = recordSession(wrappedDriver.getDriver());
				if (recordSession != null) {
					wrappedDriver.closeables.add(recordSession);
				}
				return wrappedDriver;
			}
			catch (Exception e) {
				throw new EvaluationException(e);
			}
		}
	}
	
	public static class SeleneseStepOperation extends SeleneseOperation {
		
		private String action;
		
		public SeleneseStepOperation(String action) {
			this.action = action;
		}
		
		private boolean record = true;
		
		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public Object evaluate(ExecutionContext context) throws EvaluationException {
			List arguments = new ArrayList();
			for (int i = 1; i < getParts().size(); i++) {
				Operation<ExecutionContext> argumentOperation = (Operation<ExecutionContext>) getParts().get(i).getContent();
				arguments.add(argumentOperation.evaluate(context));
			}
			if (arguments.size() < 1) {
				throw new EvaluationException("Need at least a webdriver parameter");
			}
			else if (!(arguments.get(0) instanceof WrappedDriver)) {
				throw new EvaluationException("The first parameter must be a webdriver");
			}
			WebDriver driver = arguments.get(0) instanceof WebDriver ? (WebDriver) arguments.get(0) : ((WrappedDriver) arguments.get(0)).getDriver();
			SeleneseTestCase testCase = new SeleneseTestCase();
			testCase.setRecord(record);
			SeleneseStep step = new SeleneseStep();
			step.setCommand(action);
			if (arguments.size() >= 2) {
				step.setTarget((String) arguments.get(1));
			}
			if (arguments.size() >= 3) {
				step.setValue((String) arguments.get(2));
			}
			testCase.getCommands().add(step);
			try {
				run(driver, null, testCase, context, false);
				return true;
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
		}

		public boolean isRecord() {
			return record;
		}

		public void setRecord(boolean record) {
			this.record = record;
		}
		
	}
	
	public static class SeleneseOperation extends BaseMethodOperation<ExecutionContext> {

		private boolean workaroundForClear = false;
		
		// wait at least a little bit so we can get good screenshots...
		// we want to take good screenshots
		private long timeout = 10000, sleep = 50, maxWait;
		
		private List<File> temporaryFiles = new ArrayList<File>();
		
		@Override
		public void finish() throws ParseException {
			// do nothing
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public Object evaluate(ExecutionContext context) throws EvaluationException {
			List arguments = new ArrayList();
			for (int i = 1; i < getParts().size(); i++) {
				Operation<ExecutionContext> argumentOperation = (Operation<ExecutionContext>) getParts().get(i).getContent();
				arguments.add(argumentOperation.evaluate(context));
			}
			if (arguments.size() < 2) {
				throw new IllegalArgumentException("Need at least two parameters: the webdriver instance and the file to run");
			}
			InputStream json = null;
			if (arguments.get(1) instanceof String) {
				String seleneseArgument = (String) arguments.get(1);
				// it _is_ the json
				if (seleneseArgument.trim().startsWith("{")) {
					json = new ByteArrayInputStream(seleneseArgument.getBytes(Charset.forName("UTF-8")));
				}
				else {
					json = find((String) arguments.get(1));
				}
			}
			else if (arguments.get(1) instanceof byte[]) {
				json = new ByteArrayInputStream((byte []) arguments.get(1));
			}
			else if (arguments.get(1) instanceof InputStream) {
				json = (InputStream) arguments.get(1);
			}
			else {
				throw new EvaluationException("Can not process " + arguments.get(1));
			}
			try {
				SeleneseTestFile testFile = parse(json, context);
				
				WebDriver driver = arguments.get(0) instanceof WrappedDriver
					? ((WrappedDriver) arguments.get(0)).getDriver()
					: null;
					
				List<String> list = new ArrayList<String>();
				for (int i = 2; i < arguments.size(); i++) {
					Object testsToRun = arguments.get(i);
					if (testsToRun instanceof String) {
						list.add((String) testsToRun);
					}
					else if (testsToRun instanceof Iterable) {
						for (String test : (Iterable<String>) testsToRun) {
							list.add(test);
						}
					}
				}
				List<SeleneseTestCase> tests = testFile.getTests();
				if (list.isEmpty()) {
					for (SeleneseTestCase test : tests) {
						run(driver, testFile, test, context, false);
					}
				}
				else {
					for (String testToRun : list) {
						for (SeleneseTestCase test : tests) {
							if (test.getName().equals(testToRun)) {
								run(driver, testFile, test, context, false);
							}
						}
					}
				}
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
			for (File temporaryFile : temporaryFiles) {
				temporaryFile.delete();
			}
			return true;
		}
		
		@SuppressWarnings("unchecked")
		protected void run(WebDriver driver, SeleneseTestFile testFile, SeleneseTestCase testCase, ExecutionContext context, boolean mustClose) throws EvaluationException, IOException {
			maxWait = ScriptMethods.environment("selenium.server.timeout") == null ? 60000 : new Long(ScriptMethods.environment("selenium.server.url"));
			String baseURL = testFile == null ? "" : testFile.getUrl().replaceAll("[/]+$", "");
			SeleneseStep previousStep = null;
			boolean closed = false;
			try {
				String lastComment = null;
				for (SeleneseStep step : testCase.getCommands()) {
					WebDriverRecorder recorder = (WebDriverRecorder) ScriptRuntime.getRuntime().getContext().get("selenium-screen-recorder");
					if (recorder != null && testCase.isRecord()) {
						recorder.screenshot(driver, step.getId(), "before");
					}
					try {
						if (ScriptRuntime.getRuntime().isAborted()) {
							break;
						}
						String message = step.getCommand();
						if (step.getTarget() != null && !step.getTarget().isEmpty()) {
							message += " @ " + step.getTarget();
						}
						if (step.getValue() != null && !step.getValue().isEmpty()) {
							message += ": " + step.getValue();
						}
						if (ScriptRuntime.getRuntime().getExecutionContext().isDebug()) {
							ScriptRuntime.getRuntime().getFormatter().print(message);
						}
						// the variables must be defined by now
						if (step.getCommand() != null) {
							step.setCommand(ScriptRuntime.getRuntime().getScript().getParser().substitute(step.getCommand(), context, false));
						}
						if (step.getTarget() != null) {
							step.setTarget(ScriptRuntime.getRuntime().getScript().getParser().substitute(step.getTarget(), context, false));
						}
						if (step.getValue() != null) {
							step.setValue(ScriptRuntime.getRuntime().getScript().getParser().substitute(step.getValue(), context, false));
						}
						if (step.getCommand().equalsIgnoreCase("open")) {
							driver.get(step.getTarget().matches("^http[s]*://.*") ? step.getTarget() : baseURL + step.getTarget());
						}
						else if (step.getCommand().equalsIgnoreCase("recording")) {
							if (recorder != null) {
								byte[] recording = recorder.getRecording(false);
								if (recording != null && recording.length > 0) {
									be.nabu.glue.core.impl.methods.v2.ScriptMethods.attach("selenium-screen-recording.mp4", recording, "video/mp4");
								}
							}
						}
						else if (step.getCommand().equalsIgnoreCase("pause")) {
							if (step.getTarget() != null && step.getTarget().matches("[0-9]+")) {
								Thread.sleep(Long.parseLong(step.getTarget()));
							}
							else {
								Thread.sleep(timeout);
							}
						}
						else if (step.getCommand().equalsIgnoreCase("store")) {
							Object currentValue = context.getPipeline().get(step.getValue());
							if (currentValue == null) {
								context.getPipeline().put(step.getValue(), step.getTarget());
							}
						}
						else if (step.getCommand().equalsIgnoreCase("echo")) {
							lastComment = step.getTarget();
						}
						else if (step.getCommand().equalsIgnoreCase("waitForTextPresent")) {
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
						else if (step.getCommand().equalsIgnoreCase("waitForTextNotPresent")) {
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
						else if (step.getCommand().equalsIgnoreCase("verifyTextPresent") || step.getCommand().equalsIgnoreCase("assertTextPresent")) {
							boolean fail = step.getCommand().startsWith("assert");
							String validateMessage = lastComment == null ? "Verify presence of text" : lastComment;
							lastComment = null;
							if (step.getTarget().contains("*")) {
								TestMethods.check(validateMessage, driver.getPageSource().matches(".*" + step.getTarget().replaceAll("\\*", ".*") + ".*"), step.getTarget(), fail);
							}
							else {
								TestMethods.check(validateMessage, driver.getPageSource().contains(step.getTarget()), step.getTarget(), fail);
							}
						}
						else if ((step.getValue() == null || step.getValue().isEmpty()) && (step.getCommand().equalsIgnoreCase("verifyTextNotPresent") || step.getCommand().equalsIgnoreCase("assertTextNotPresent"))) {
							String validateMessage = lastComment == null ? "Verify absence of text" : lastComment;
							lastComment = null;
							TestMethods.check(validateMessage, !driver.getPageSource().contains(step.getTarget()), step.getTarget(), step.getCommand().startsWith("assert"));
						}
						else if (step.getCommand().equalsIgnoreCase("windowMaximize")) {
							driver.manage().window().maximize();
						}
						else if (step.getCommand().equalsIgnoreCase("assertTitle")) {
							boolean result = driver.getTitle().equals(step.getTarget());
							TestMethods.check(lastComment, result, result ? "Check if title is correct" : "Check if title is correct: " + step.getTarget() + " != " + driver.getTitle(), false);
						}
						else if (step.getCommand().equalsIgnoreCase("waitForPageToLoad")) {
							// TODO
						}
						else if (step.getCommand().equalsIgnoreCase("waitForFrameToLoad")) {
							// TODO
						}
						// the timeout is used for "wait*" and "open*" methods, it is expressed in milliseconds
						else if (step.getCommand().equalsIgnoreCase("setTimeout")) {
							this.timeout = new Long(step.getTarget());
						}
						// the speed is used to wait after every step executed, it is expressed in milliseconds 
						else if (step.getCommand().equalsIgnoreCase("setSpeed")) {
							this.sleep = new Long(step.getTarget());
						}
						else if (step.getCommand().equalsIgnoreCase("close")) {
							driver.close();
							closed = true;
							break;
						}
						else if (step.getCommand().equalsIgnoreCase("waitForPopUp")) {
							// do nothing
						}
						else if (step.getCommand().equalsIgnoreCase("captureEntirePageScreenshot")) {
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
							// put it on the pipeline
							Map<String, byte[]> screenshots = (Map<String, byte[]>) ScriptRuntime.getRuntime().getContext().get("screenshots");
							ScriptRuntime.getRuntime().getExecutionContext().getPipeline().put("$screenshot" + screenshots.size(), screenshot);
							if (step.getTarget() != null && !step.getTarget().isEmpty()) {
								ScriptRuntime.getRuntime().getExecutionContext().getPipeline().put(step.getTarget().replaceAll("[^\\w]+", "_"), screenshot);
							}
							screenshots.put(name.endsWith(".png") ? name : name + ".png", screenshot);
						}
						else if (step.getCommand().equalsIgnoreCase("selectWindow")) {
							if (previousStep != null && previousStep.getCommand().equalsIgnoreCase("waitForPopup")) {
								WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(this.timeout));
								wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(step.getTarget()));
							}
							else {
								driver.switchTo().window(step.getTarget().equals("null") ? null : step.getTarget());
							}
						}
						else if (step.getCommand().equalsIgnoreCase("assertConfirmation")) {
							Alert alert = driver.switchTo().alert();
							if (alert.getText().equals(step.getTarget())) {
								alert.accept();
							}
						}
						else if (step.getCommand().equalsIgnoreCase("setWindowSize")) {
							if (step.getTarget() != null) {
								String[] split = step.getTarget().split("[\\s]*x[\\s]*");
								driver.manage().window().setSize(new Dimension(Integer.parseInt(split[0]), Integer.parseInt(split[1])));
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
									if (step.getCommand().equalsIgnoreCase("verifyAttribute") || step.getCommand().equalsIgnoreCase("assertAttribute")) {
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
							
							// when we wait for a text to appear (or disappear) we need to reselect the target element in every loop!
							// for example we had an issue where a dropdown list is filtered by what you type and you need to click on the filtered value
							// from time to time the code would be too fast as in, it would select the first element in the list but by the time it got to the code to actually click() it
							// the javascript filter had removed said element from the dom triggering a disconnected element exception
							// a wait for text in a specific element reselects the element and waits for it to appear there
							if (step.getCommand().equalsIgnoreCase("waitForText") || step.getCommand().equalsIgnoreCase("waitForNotText")) {
								Date waitStart = new Date();
								boolean succeeded = false;
								while (!succeeded && new Date().getTime() - waitStart.getTime() < this.timeout) {
									ExpectedCondition<?> presenceOfElementLocated = ExpectedConditions.presenceOfElementLocated(by);
									WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(this.timeout));
									wait.until(presenceOfElementLocated);
									try {
										WebElement element = driver.findElement(by);
										if (step.getValue().contains("*")) {
											if (element.getText().matches(".*" + step.getValue().replaceAll("\\*", ".*") + ".*")) {
												succeeded = true;
											}
										}
										else {
											if (element.getText().toLowerCase().contains(step.getValue().toLowerCase())) {
												succeeded = true;
											}
										}
									}
									catch (StaleElementReferenceException e) {
										// retry, the element is no longer in the dom
									}
								}
								if (!succeeded) {
									throw new EvaluationException("Failed to execute the command '" + step.getCommand() + "' within the given timeframe");
								}
							}
							else {
								ExpectedCondition<?> presenceOfElementLocated = null;
								
								if (step.getCommand().equalsIgnoreCase("waitForElementNotPresent") || ((step.getCommand().matches("(assert|validate)XpathCount") && "0".equals(step.getValue())))) {
									if (!driver.findElements(by).isEmpty()) {
										presenceOfElementLocated = ExpectedConditions.not(ExpectedConditions.presenceOfElementLocated(by));
									}
								}
								else {
									presenceOfElementLocated = ExpectedConditions.presenceOfElementLocated(by);
								}
								if (presenceOfElementLocated != null) {
									try {
										WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(this.timeout));
										wait.until(presenceOfElementLocated);
									}
									catch (TimeoutException e) {
										// in the case of a timeout and you simply put verify or iselement present
										if (step.getCommand().equalsIgnoreCase("verifyElementPresent") || step.getCommand().equalsIgnoreCase("isElementPresent")) {
											TestMethods.check(lastComment, false, "false", false);
											lastComment = null;
											continue;
										}
										throw e;
									}
								}
								
								// commands that work on multiple elements
								if (step.getCommand().equalsIgnoreCase("assertXpathCount") || step.getCommand().equalsIgnoreCase("verifyXpathCount")) {
									List<WebElement> findElements = driver.findElements(by);
									String validateMessage = lastComment == null ? "Verify amount of items matching " + step.getTarget() : lastComment;
									lastComment = null;
									if (step.getCommand().startsWith("assert")) {
										TestMethods.confirmEquals(validateMessage, Integer.parseInt(step.getValue()), findElements.size());
									}
									else {
										TestMethods.validateEquals(validateMessage, Integer.parseInt(step.getValue()), findElements.size());
									}
								}
								else if (step.getCommand().equalsIgnoreCase("assertNotXpathCount") || step.getCommand().equalsIgnoreCase("verifyNotXpathCount")) {
									List<WebElement> findElements = driver.findElements(by);
									String validateMessage = lastComment == null ? "Verify not amount of items matching " + step.getTarget() : lastComment;
									lastComment = null;
									if (step.getCommand().startsWith("assert")) {
										TestMethods.confirmNotEquals(validateMessage, Integer.parseInt(step.getValue()), findElements.size());
									}
									else {
										TestMethods.validateNotEquals(validateMessage, Integer.parseInt(step.getValue()), findElements.size());
									}
								}
								else {
									WebElement element = driver.findElement(by);
									if (element == null) {
										throw new EvaluationException("Can not find element " + by);
									}
									if (step.getCommand().equalsIgnoreCase("type")) {
										// as asked here: http://stackoverflow.com/questions/29919576/selenium-element-clear-triggers-javascript-before-sendkeys
										// and found here: http://stackoverflow.com/questions/19833728/webelement-clear-fires-javascript-change-event-alternatives
										// logged as a "works as designed" bug here: https://code.google.com/p/selenium/issues/detail?id=214
										// the clear() actually triggers an onchange event
										// a number of controls (e.g. vaadin) use the onchange event and the fact that the field is now empty to fill in for example an empty template
										// e.g. a date field would be filled in with the format of said field
										// in our case a clear() + sendKeys("2015/04/29") actually resulted in "yyyy/MM/dd2015/04/29" because after the clear() the template was automatically inserted
										// so for input stuff we don't send a clear()
										if (!workaroundForClear || !element.getTagName().equalsIgnoreCase("input")) {
											element.clear();
										}
										else if (element.getText() != null && !element.getText().isEmpty()) {
											// for older selenium versions (2.43.1) and/or older versions of firefox (27) the second delete statement does _not_ work (it does work on selenium 2.45 and firefox 36.0.1)
											// after trying many combinations with ctrl+a etc, this proved to be the only one that works
											// note that element.getText() appears to send back an empty string in this version combination so we had to resort to the value attribute which should available for all input elements
											// As seen: http://stackoverflow.com/questions/13721213/gettext-returns-a-blank-in-selenium-even-if-the-text-is-not-hidden-tried-javas
											// note that an alternative would be getAttribute("innerHTML")
											Keys [] keys = new Keys[element.getAttribute("value").length()];
											for (int i = 0; i < keys.length; i++) {
												keys[i] = Keys.BACK_SPACE;
											}
											Actions navigator = new Actions(driver);
											navigator.click(element)
												.sendKeys(Keys.END)
												.sendKeys(Keys.chord(keys))
												.build()
												.perform();
											// currently leaving this delete action in there
											navigator = new Actions(driver);
										    navigator.click(element)
										        .sendKeys(Keys.END)
										        .keyDown(Keys.SHIFT)
										        .sendKeys(Keys.HOME)
										        .keyUp(Keys.SHIFT)
										        .sendKeys(Keys.BACK_SPACE)
										        .build()
										        .perform();
										}
										// you are requesting a file upload
										if (element.getTagName().equalsIgnoreCase("input") && element.getAttribute("type").equalsIgnoreCase("file")) {
											String fileName = step.getValue().replaceAll(".*[\\\\/]+([^\\\\/]+)$", "$1");
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
											element.sendKeys(step.getValue());
										}
									}
									else if (step.getCommand().equalsIgnoreCase("storeText")) {
										context.getPipeline().put(step.getValue(), element.getText());
									}
									else if (step.getCommand().equalsIgnoreCase("doubleClick")) {
										Actions action = new Actions(driver);
										// there is apparently a bug where you have to send this before the double click will work: http://stackoverflow.com/questions/25756876/selenium-webdriver-double-click-doesnt-work
										driver.findElement(by).sendKeys("");
										action.doubleClick(element).perform();
									}
									else if (step.getCommand().equalsIgnoreCase("waitForElementPresent") || step.getCommand().equalsIgnoreCase("verifyElementPresent") || step.getCommand().equalsIgnoreCase("isElementPresent") || step.getCommand().equalsIgnoreCase("assertElementPresent")) {
										// we already waited for the element, so keep going
										TestMethods.check(lastComment, true, "true", false);
										lastComment = null;
									}
									else if (step.getCommand().equalsIgnoreCase("verifyText") || step.getCommand().equalsIgnoreCase("assertText") || step.getCommand().equalsIgnoreCase("verifyAttribute") || step.getCommand().equalsIgnoreCase("assertAttribute")) {
										boolean fail = step.getCommand().startsWith("assert");
										String type = step.getCommand().replaceAll("^(wait|verify|assert)", "").toLowerCase();
										String text = attribute == null ? element.getText() : element.getAttribute(attribute);
										String validateMessage = lastComment == null ? "Verify presence of " + type + " in " + step.getTarget() : lastComment;
										lastComment = null;
										if (step.getValue().contains("*")) {
											boolean matches = text.matches(".*" + step.getValue().replaceAll("\\*", ".*") + ".*");
											TestMethods.check(validateMessage, matches, matches ? step.getValue() : text + " !~ " + step.getValue(), fail);
										}
										else {
											boolean contains = text.toLowerCase().contains(step.getValue().toLowerCase());
											TestMethods.check(validateMessage, contains, contains ? step.getValue() : text + " !# " + step.getValue(), fail);
										}
									}
									else if (step.getCommand().equalsIgnoreCase("verifyNotText") || step.getCommand().equalsIgnoreCase("assertNotText") || step.getCommand().equalsIgnoreCase("verifyNotAttribute") || step.getCommand().equalsIgnoreCase("assertNotAttribute")) {
										String type = step.getCommand().replaceAll("^(verify|assert)Not", "").toLowerCase();
										String validateMessage = lastComment == null ? "Verify presence of " + type + " in " + step.getTarget() : lastComment;
										lastComment = null;
										TestMethods.check(validateMessage, !element.getText().contains(step.getValue()), step.getValue(), step.getCommand().startsWith("assert"));
									}
									else if (step.getCommand().equalsIgnoreCase("clickAndWait")) {
										element.click();
										try {
											Thread.sleep(step.getValue() == null || step.getValue().isEmpty() ? this.timeout : new Long(step.getValue()));
										}
										catch (InterruptedException e) {
											// continue
										}
									}
									else if (step.getCommand().equalsIgnoreCase("click") || (step.getCommand().equalsIgnoreCase("clickAt") && (step.getValue() == null || step.getValue().trim().isEmpty()))) {
										element.click();
									}
									else if (step.getCommand().equalsIgnoreCase("clickAt")) {
										Actions action = new Actions(driver);
										String [] parts = step.getValue().split(",");
										action.moveToElement(element, new Integer(parts[0]), parts.length > 1 ? new Integer(parts[1]) : 0).click().perform();
									}
									else if (step.getCommand().equalsIgnoreCase("select")) {
										Select select = new Select(element);
										if (step.getValue() != null && !step.getValue().trim().isEmpty()) {
											if (step.getValue().startsWith("value=")) {
												select.selectByValue(step.getValue().substring("value=".length()));
											}
											// not supported for now...
											else if (step.getValue().startsWith("id=")) {
												throw new IllegalArgumentException("Currently the select by id does not work");
											}
											else if (step.getValue().startsWith("index=")) {
												select.selectByIndex(Integer.parseInt(step.getValue().substring("index=".length())));
											}
											// by content (this is the default)
											else if (step.getValue().startsWith("label=")) {
												select.selectByVisibleText(step.getValue().substring("label=".length()));
											}
											else {
												select.selectByVisibleText(step.getValue().trim());
											}
										}
									}
									else if (step.getCommand().equalsIgnoreCase("mouseOver")) {
										Actions action = new Actions(driver);
										String [] parts = step.getValue().trim().isEmpty() ? new String[0] : step.getValue().split(",");
										action.moveToElement(element, parts.length > 0 ? new Integer(parts[0]) : 0, parts.length > 1 ? new Integer(parts[1]) : 0).perform();
									}
									else if (step.getCommand().equalsIgnoreCase("mouseDownAt")) {
										Actions action = new Actions(driver);
										String [] parts = step.getValue().trim().isEmpty() ? new String[0] : step.getValue().split(",");
										action.moveToElement(element, parts.length > 0 ? new Integer(parts[0]) : 0, parts.length > 1 ? new Integer(parts[1]) : 0).clickAndHold().perform();
									}
									else if (step.getCommand().equalsIgnoreCase("mouseMoveAt")) {
										Actions action = new Actions(driver);
										String [] parts = step.getValue().trim().isEmpty() ? new String[0] : step.getValue().split(",");
										action.moveToElement(element, parts.length > 0 ? new Integer(parts[0]) : 0, parts.length > 1 ? new Integer(parts[1]) : 0).perform();
									}
									else if (step.getCommand().equalsIgnoreCase("mouseUpAt")) {
										Actions action = new Actions(driver);
										String [] parts = step.getValue().trim().isEmpty() ? new String[0] : step.getValue().split(",");
										action.moveToElement(element, parts.length > 0 ? new Integer(parts[0]) : 0, parts.length > 1 ? new Integer(parts[1]) : 0).release().perform();
									}
									else {
										throw new EvaluationException("Unknown selenium command: " + step.getCommand());
									}
								}
							}
						}
						previousStep = step;
						if (recorder != null && testCase.isRecord() && screenshotAfter) {
							recorder.screenshot(driver, step.getId(), "after");
						}
						if (sleep > 0) {
							try {
								Thread.sleep(sleep);
							}
							catch (InterruptedException e) {
								// continue
							}
						}
					}
					catch (Exception e) {
						// we definitely want a screenshot in this case to see why it may have failed
						if (recorder != null) {
							recorder.screenshot(driver, step.getId(), "after");
						}
						throw new EvaluationException("Failed to execute step: " + step, e);
					}
				}
			}
			finally {
				if (!closed && mustClose) {
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
		
		public static SeleneseTestFile parse(InputStream xml, ExecutionContext context) throws EvaluationException, IOException {
			try {
				Charset charset = ScriptRuntime.getRuntime().getScript().getCharset();
				String originalContent = new String(ScriptMethods.bytes(xml), charset);
				// in selenium you can make use of variables while running, so allow null for now
				originalContent = ScriptRuntime.getRuntime().getScript().getParser().substitute(originalContent, context, true);
				xml = new ByteArrayInputStream(originalContent.getBytes(charset));
				JSONBinding binding = new JSONBinding((ComplexType) BeanResolver.getInstance().resolve(SeleneseTestFile.class), charset);
				binding.setIgnoreUnknownElements(true);
				ComplexContent unmarshal = binding.unmarshal(xml, new Window[0]);
				return TypeUtils.getAsBean(unmarshal, SeleneseTestFile.class);
			}
			catch (Exception e) {
				throw new EvaluationException("Could not parse selenium JSON file", e);
			}
		}
	}

	@XmlRootElement(name = "testFile")
	public static class SeleneseTestFile {
		private String url, name, id;
		private List<SeleneseTestCase> tests;
		public String getUrl() {
			return url;
		}
		public void setUrl(String url) {
			this.url = url;
		}
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public String getId() {
			return id;
		}
		public void setId(String id) {
			this.id = id;
		}
		public List<SeleneseTestCase> getTests() {
			return tests;
		}
		public void setTests(List<SeleneseTestCase> tests) {
			this.tests = tests;
		}
	}
	
	public static class SeleneseTestCase {
		private List<SeleneseStep> commands = new ArrayList<SeleneseStep>();
		private String name, id;
		private boolean record = true;

		public List<SeleneseStep> getCommands() {
			return commands;
		}

		public void setCommands(List<SeleneseStep> commands) {
			this.commands = commands;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public boolean isRecord() {
			return record;
		}

		public void setRecord(boolean record) {
			this.record = record;
		}
	}
	
	public static class SeleneseStep {
		private String command, target, value, id, comment;

		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

		public String getTarget() {
			return target;
		}

		public void setTarget(String target) {
			this.target = target;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getComment() {
			return comment;
		}

		public void setComment(String comment) {
			this.comment = comment;
		}
		
	}

	@Override
	public List<MethodDescription> getAvailableMethods() {
		List<MethodDescription> descriptions = new ArrayList<MethodDescription>();
		descriptions.add(new SimpleMethodDescription("selenium", "selenese", "This will run a selenese script created using the selenium IDE",
			Arrays.asList(new ParameterDescription [] {
				new SimpleParameterDescription("driver", "You must pass in a driver instance created with selenium.driver()", "webdriver"),
				new SimpleParameterDescription("script", "You can pass in the name of the file that holds the selense or alternatively you can pass in byte[] or InputStream", "String, byte[], InputStream"),
				new SimpleParameterDescription("tests", "The tests to run in the script", "String", true)
			}),
			new ArrayList<ParameterDescription>()));
		descriptions.add(new SimpleMethodDescription("selenium", "webdriver", "Returns a webdriver that can be used to run selenese() on",
			Arrays.asList(new ParameterDescription [] { 
				new SimpleParameterDescription("browser", "You can pass in the name of the target browser (e.g. firefox) as the second parameter", "WebDriver, String"),
				new SimpleParameterDescription("language", "If you have passed in the name of the browser as the second parameter, you can optionally pass in a language setting as well", "String")
			}),
			new ArrayList<ParameterDescription>()));
		descriptions.add(new SimpleMethodDescription("selenium", "recording", "Returns the recording in bytes (if available).",
				Arrays.asList(new ParameterDescription [] { 
					new SimpleParameterDescription("browser", "You can pass in the name of the target browser (e.g. firefox) as the second parameter", "WebDriver, String"),
					new SimpleParameterDescription("language", "If you have passed in the name of the browser as the second parameter, you can optionally pass in a language setting as well", "String")
				}),
				new ArrayList<ParameterDescription>()));
		return descriptions;
	}
	
	private static DesiredCapabilities getSafariCapabilities(String language) {
//		DesiredCapabilities capabilities = DesiredCapabilities.safari();
		DesiredCapabilities capabilities = new DesiredCapabilities();
		return capabilities;
	}
	
	private static FirefoxOptions getFirefoxOptions(String language, boolean headless) {
		if (System.getProperty("webdriver.gecko.driver") == null && ScriptMethods.environment("webdriver.gecko.driver") != null) {
			System.setProperty("webdriver.gecko.driver", ScriptMethods.environment("webdriver.gecko.driver"));
		}
		// use marionette?
//		capabilities.setCapability("marionette", true);
		FirefoxProfile profile = getFirefoxProfile(language);
		FirefoxOptions options = new FirefoxOptions();
		options.setHeadless(headless);
		options.setProfile(profile);
		return options;
	}

	private static FirefoxProfile getFirefoxProfile(String language) {
		FirefoxProfile profile = new FirefoxProfile();
		if (language != null) {
			profile.setPreference("intl.accept_languages", language);
		}
		profile.setPreference("startup.homepage_welcome_url.additional", "");
		// in firefox 43, they enabled signatures for plugins
		// this is also fixed in firefox driver version 2.48
		profile.setPreference("xpinstall.signatures.required", false);
		return profile;
	}
	
	private static DesiredCapabilities getFirefoxCapabilities(String language) {
		DesiredCapabilities capabilities = new DesiredCapabilities();	// DesiredCapabilities.firefox()
//		capabilities.setCapability(FirefoxDriver.PROFILE, getFirefoxProfile(language));
		capabilities.setCapability("marionette", true);
		return capabilities;
	}

	private static WebDriver getEdgeDriver(String language, boolean headless) {
//		WebDriverManager.edgedriver().setup();
		EdgeOptions options = new EdgeOptions();
		return new EdgeDriver(options);
	}
	
	private static WebDriver getFirefoxDriver(String language, boolean headless) {
//		WebDriverManager.firefoxdriver().setup();
		return new FirefoxDriver(getFirefoxOptions(language, headless));
	}
	
	private static WebDriver getSafariDriver(String language, boolean headless) {
		SafariOptions safariOptions = new SafariOptions();
		return new SafariDriver(safariOptions);
	}
	
//	private static WebDriver getOperaDriver(String language, boolean headless) {
//		WebDriverManager.operadriver().setup();
//		return new OperaDriver(getOperaCapabilities(language));
//	}
//	
	private static WebDriver getChromeDriver(String language, boolean headless) {
//		WebDriverManager.chromedriver().setup();
		// if the system property is not set but you have it configured in the glue properties, push it to the system properties
		if (System.getProperty("webdriver.chrome.driver") == null && ScriptMethods.environment("webdriver.chrome.driver") != null) {
			System.setProperty("webdriver.chrome.driver", ScriptMethods.environment("webdriver.chrome.driver"));
		}
		return new ChromeDriver(getChromeOptions(language, headless));
	}
	
	public static class WebDriverRecorder implements Closeable {
		
		private byte [] recording = null;
		private List<byte[]> images = new ArrayList<byte[]>();
		private Path directory;
		private int counter = 10000;
		
		private String getPath() {
			return directory.toFile().getAbsolutePath();
		}
		
		public void screenshot(WebDriver driver, String stepId, String suffix) {
			if (directory != null) {
				byte [] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
				try {
					if (screenshotInsteadOfVideo) {
						be.nabu.glue.core.impl.methods.v2.ScriptMethods.attach("selenium-screenshot-" + stepId + "-" + suffix + ".png", screenshot, "image/png");
					}
					else {
						FileMethods.write(getPath() + "/" + counter++ + "-screenshot.png", screenshot);
					}
				}
				catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}

		private void start(WebDriver driver) throws IOException {
			directory = Files.createTempDirectory("screen-recorder");
			if (driver instanceof ChromeDriver && !screenshotInsteadOfVideo) {
				DevTools devTools = ((ChromeDriver) driver).getDevTools();
				try {
					devTools.createSession();
					Optional<Integer> maxWidth = Optional.ofNullable(null);
					Optional<Integer> maxHeight = Optional.ofNullable(null);
					Optional<Integer> everyNthFrame = Optional.ofNullable(1);
					devTools.send(Page.startScreencast(Optional.of(StartScreencastFormat.PNG), Optional.of(100), maxWidth, maxHeight, everyNthFrame));
					
					devTools.addListener(Page.screencastFrame(), new Consumer<ScreencastFrame>() {
						@Override
						public void accept(ScreencastFrame frame) {
							String base64Image = frame.getData();
							try {
								byte[] bytes = IOUtils.toBytes(TranscoderUtils.transcodeBytes(IOUtils.wrap(base64Image.getBytes("ASCII"), true), new Base64Decoder()));
								FileMethods.write(getPath() + "/" + counter++ + "-screenshot.png", bytes);
							}
							catch (Exception e) {
								e.printStackTrace();
							}
							Page.screencastFrameAck(frame.getSessionId());
						}
					});
				}
				catch (Exception e) {
					logger.error("Could not record session", e);
				}
			}
			logger.info("Using directory for screen recording: " + getPath());
			ScriptRuntime.getRuntime().getContext().put("selenium-screen-recorder", this);
		}

		@Override
		public void close() throws IOException {
			cleanup();
		}
		
		public byte [] getRecording(boolean force) throws IOException {
			if (this.recording == null || force) {
				this.finalizeRecording();
				if (directory != null) {
					File file = new File(getPath(), "result.mp4");
					if (file.exists()) {
						try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
							this.recording = IOUtils.toBytes(IOUtils.wrap(input));
						}
					}
				}
			}
			return this.recording;
		}

		public void cleanup() {
			if (directory != null) {
				File file = new File(getPath());
				if (file.isDirectory()) {
					for (File child : file.listFiles()) {
						child.delete();
					}
					file.delete();
				}
				directory = null;
			}
		}
		
		public void finalizeRecording() {
			if (directory != null && !screenshotInsteadOfVideo) {
				try {
					// use ffmpeg to generate a video from the images
					ProcessBuilder processBuilder = new ProcessBuilder(
						"ffmpeg", 
						"-y",
						"-r", "25",
						// this slows the recording down!
						"-vf", "setpts=15*PTS",
						//        			"-pix_fmt", "argb",
						//        			"-s", "1395x727",
						//        			"-c:v", "libx264",
						"-f", "mp4", getPath() + "/result.mp4", 
						//"-i", "-" // can also write "pipe:"
						//        			"-i", "/tmp/test"
						"-pattern_type", "glob", "-i", getPath() + "/*.png"
					);
					
					processBuilder.redirectErrorStream(false);
					Process process = processBuilder.start();
					process.waitFor();
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		public List<byte[]> getImages() {
			return images;
		}
	}
	
	private static Closeable recordSession(WebDriver driver) {
		WebDriverRecorder recorder = new WebDriverRecorder();
		try {
			recorder.start(driver);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return recorder;
	}
	
	private static WebDriver getInternetExplorerDriver(String language, boolean headless) {
//		WebDriverManager.iedriver().setup();
		InternetExplorerOptions options = new InternetExplorerOptions();
		return new InternetExplorerDriver(options);
	}

	private static ChromeOptions getChromeOptions(String language, boolean headless) {
//		WebDriverManager.chromiumdriver().setup();
		ChromeOptions options = new ChromeOptions();
		options.setHeadless(headless);
		if (language != null) {
			options.addArguments("--lang=" + language);
		}
		if (headless) {
			options.addArguments("--headless");
			options.addArguments("start-maximized");
			options.addArguments("disable-infobars");
			options.addArguments("--disable-extensions");
			options.addArguments("--disable-gpu");
			options.addArguments("--disable-dev-shm-usage");
			options.addArguments("--no-sandbox");
		}
		return options;
	}
	
	private static DesiredCapabilities getChromeCapabilities(String language) {
//		DesiredCapabilities capabilities = DesiredCapabilities.chrome();
		DesiredCapabilities capabilities = new DesiredCapabilities();
		capabilities.setCapability(ChromeOptions.CAPABILITY, getChromeOptions(language, false));
		return capabilities;
	}
	
	public static WebDriver getRemoteDriver(URL url, DesiredCapabilities capabilities) {
		return new RemoteWebDriver(url, capabilities);
	}
	
	private static WebDriver getDriver(String browser, String language, String url, boolean headless) throws MalformedURLException {
		if (url == null) {
			url = ScriptMethods.environment("selenium.server.url");
		}
		// local execution
		if (url == null) {
			// if we did not ask for headless but we are running on a headless system, switch
			if (!headless) {
				headless = java.awt.GraphicsEnvironment.isHeadless();
			}
			if (browser != null && (browser.equalsIgnoreCase("chrome") || browser.equalsIgnoreCase("chromium"))) {
				return getChromeDriver(language, headless);
			}
			else if (browser != null && browser.toLowerCase().equals("edge")) {
				return getEdgeDriver(language, headless);
			}
			else if (browser != null && (browser.equalsIgnoreCase("explorer") || browser.equals("internet explorer"))) {
				return getInternetExplorerDriver(language, headless);
			}
//			else if (browser != null && browser.equalsIgnoreCase("opera")) {
//				return getOperaDriver(language, headless);
//			}
			else if (browser != null && browser.equalsIgnoreCase("safari")) {
				return getSafariDriver(language, headless);
			}
			else {
				return getFirefoxDriver(language, headless);
			}
		}
		else {
			if (browser != null && (browser.equalsIgnoreCase("chrome") || browser.equalsIgnoreCase("chromium"))) {
				return getRemoteDriver(new URL(url), getChromeCapabilities(language));
			}
//			else if (browser != null && browser.toLowerCase().equals("edge")) {
//				return getRemoteDriver(new URL(url), getEdgeCapabilities(language));
//			}
//			else if (browser != null && (browser.equalsIgnoreCase("explorer") || browser.equals("internet explorer"))) {
//				return getRemoteDriver(new URL(url), getInternetExplorerCapabilities(language));
//			}
//			else if (browser != null && browser.equalsIgnoreCase("opera")) {
//				return getRemoteDriver(new URL(url), getOperaCapabilities(language));
//			}
			else if (browser != null && browser.equalsIgnoreCase("safari")) {
				return getRemoteDriver(new URL(url), getSafariCapabilities(language));
			}
			else {
				return getRemoteDriver(new URL(url), getFirefoxCapabilities(language));
			}
		}
	}
}
