package de.moritzf.airplaycapture.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sends the bare host name to the Vaadin application. */
@Controller
public class RootController {

    @GetMapping("/")
    public String root() {
        return "redirect:/app/";
    }
}
