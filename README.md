# Gamma Playground

[![Join the chat at https://gitter.im/kovasb/gamma](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/kovasb/gamma?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Some examples of how to use [Gamma](https://github.com/kovasb/gamma) and [Gamma Driver](https://github.com/kovasb/gamma-driver). In particular, it contains the code translations of the popular [Learn WebGL Series](http://learningwebgl.com/blog/?page_id=1217)

Currently lessons 1-10 are ported. Please feel free to take a shot at porting the remaining 6, it's far easier than you might think! Head over to [Issues](https://github.com/sgrove/gamma-playground/issues) to see the remaining lessons.

## Warning
Please don't take the state of this code as finished - feel free to clean it up, send pull requests, and open issues. This should become a highly-polished introduction to gamma, from beginner usage to advanced.

## Further warning

This code is in *heavy* flux as both usage of Gamma and Gamma Driver, and the projects themselves, are ironed out. Examples may be slightly broken or slow at any given time until all three projects settle down a bit. Feel free to open an issue if one of the examples doesn't work, with a detailed list of repro-steps.

## Development

Open a terminal and type `lein repl` to start a Clojure REPL
(interactive prompt).

In the REPL, type

```clojure
(run)
(browser-repl)
```

The call to `(run)` does two things, it starts the webserver at port
10555, and also the Figwheel server which takes care of live reloading
ClojureScript code and CSS. Give them some time to start.

Running `(browser-repl)` starts the Weasel REPL server, and drops you
into a ClojureScript REPL. Evaluating expressions here will only work
once you've loaded the page, so the browser can connect to Weasel.

When you see the line `Successfully compiled "resources/public/app.js"
in 21.36 seconds.`, you're ready to go. Browse to
`http://localhost:10555` and enjoy.

**Attention: It is not longer needed to run `lein figwheel`
  separately. This is now taken care of behind the scenes**
