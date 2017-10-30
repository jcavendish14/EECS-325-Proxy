# EECS 325 Proxy

Creates a simple Java proxy that can process HTTP (GET, POST) requests. Uses multithreading
so multiple requests can be made at the same time. Also has a DNS cache so IP addresses
will be stored for up to 30 seconds.

## Using the Proxy

### Port Number

The default port number for this proxy is 5002, but another port number can be used by
adding the arguments **-port <number>**.

### Instructions

1. Configure your browser to use the proxy
2. Compile the proxy server by invoking **javac proxyd.java**
3. Execute the command **java proxyd -port <number>**
4. Browse the internet like usual (HTTPS requests will not work)

## Testing and Bugs

### Browser and Websites for Testing

I used Google Chrome to test the proxy and the following websites:

* [case.edu](https://www.case.edu) (General browsing)
* [htmlpurifier.org](http://htmlpurifier.org/demo.php) (GET requests)
* [htmlpurifier.org](http://htmlpurifier.org/demo.php?post) (POST requests)

### Problems

I have yet to run into any problems related to my proxy.

