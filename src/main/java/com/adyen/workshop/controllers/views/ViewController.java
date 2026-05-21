package com.adyen.workshop.controllers.views;

import com.adyen.workshop.configurations.ApplicationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class ViewController {
    private final Logger log = LoggerFactory.getLogger(ViewController.class);

    private final ApplicationConfiguration applicationConfiguration;

    public ViewController(ApplicationConfiguration applicationConfiguration) {
        this.applicationConfiguration = applicationConfiguration;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/preview")
    public String preview(@RequestParam String type, Model model) {
        model.addAttribute("type", type);
        return "preview";
    }

    @GetMapping("/checkout")
    public String checkout(@RequestParam String type, Model model) {
        model.addAttribute("type", type);
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        return "checkout";
    }

    @GetMapping("/result/{type}")
    public String result(@PathVariable String type, Model model) {
        model.addAttribute("type", type);
        return "result";
    }

    @GetMapping("/redirect")
    public String redirect(Model model) {
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        return "redirect";
    }

    /**
     * GET /subscription — renders the page where a shopper subscribes
     * to a recurring service (Module 2 / Phase 8).
     *
     * Optional ?shopperReference= query parameter lets the workshop participant
     * test multiple "subscribers" from the same browser. Defaults to a stable
     * value so repeated tests update the same row in the token store instead of
     * filling it with duplicates.
     */
    @GetMapping("/subscription")
    public String subscription(
            @RequestParam(name = "shopperReference", required = false, defaultValue = "workshop-shopper-001")
            String shopperReference,
            Model model) {
        log.info("Rendering /subscription page for shopperReference={}", shopperReference);
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        model.addAttribute("shopperReference", shopperReference);
        return "subscription";
    }
}
