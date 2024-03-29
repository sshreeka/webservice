package com.springboot.springbootapp.controller;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;


import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.GetItemOutcome;

import com.amazonaws.services.dynamodbv2.document.internal.InternalUtils;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.xspec.S;
import com.springboot.springbootapp.entity.Image;
import com.springboot.springbootapp.entity.User;

import com.springboot.springbootapp.repository.ImageRepository;
import com.springboot.springbootapp.service.EmailSNSService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.springboot.springbootapp.errors.RegistrationStatus;
import com.springboot.springbootapp.repository.UserRepository;
import com.springboot.springbootapp.service.S3BucketStorageService;
import com.springboot.springbootapp.service.UserService;
import com.springboot.springbootapp.validators.UserValidator;

import java.time.OffsetDateTime;
import java.util.*;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import com.timgroup.statsd.StatsDClient;

import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;


@RestController

public class UserController {
    private final static Logger logger = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private UserValidator userValidator;

    @Autowired
    private UserRepository repository;

    @Autowired
    S3BucketStorageService service;

    @Autowired
    private StatsDClient statsd;

    @Autowired
    BCryptPasswordEncoder bCryptPasswordEncoder;


    @Autowired
    ImageRepository imageRepository;

    private DynamoDB dynamoDB;

    @Autowired
    EmailSNSService snsService;

    static AmazonDynamoDB dynamodbClient = null;

    @InitBinder
    private void initBinder(WebDataBinder binder) {
        binder.setValidator(userValidator);
    }

    @GetMapping(value = "v1/user/self")
    public ResponseEntity getUser(HttpServletRequest request) {
        statsd.increment("Calls - Get user");

        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String sd = authorizationHeader.replace("Basic ", "");
        byte[] decodedBytes = Base64.getDecoder().decode(sd);
        String decoded = new String(decodedBytes);
        String[] parts = decoded.split(":");
        if (userService.isEmailPresent(parts[0])){
            User user = userService.getUser(parts[0]);
            if(!user.isVerified()) {
                System.out.println("User is not yet verified");
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(user);
    }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Not authorized");
    }


    @PutMapping(value="v1/user/self")
    public ResponseEntity updateUser(@RequestBody User user, HttpServletRequest request) {
        statsd.increment("Calls - Edit user");

        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String sd = authorizationHeader.replace("Basic ", "");
        byte[] decodedBytes = Base64.getDecoder().decode(sd);
        String decoded = new String(decodedBytes);
        String[] parts = decoded.split(":");

        User existingUser = repository.findById(user.getId()).orElse(null);

        if (!existingUser.getEmailId().equals(parts[0])){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(" 400 Bad Request, cant update ");

        }
        if (userService.pwdvalidate(user.getPassword())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(user.getId()+":" +"Invalid password");

        }
        if ((user.getEmailId() != null) || (user.getPassword().equals("") || (user.getFname().equals("")) || (user.getLname().equals("")))){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(" 400 Bad Request");
        } else {
            if(!user.isVerified()) {
                System.out.println("User is not yet verified");
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            }
            User updated_user = userService.updateUser(user);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(updated_user);
        }
    }
    @PostMapping(value="v1/user/")
    public ResponseEntity register(@Valid @RequestBody User user, BindingResult errors, HttpServletResponse response) throws Exception{
        statsd.increment("Calls - Update user");

        RegistrationStatus registrationStatus;

        if(errors.hasErrors()) {
            registrationStatus = userService.getRegistrationStatus(errors);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(registrationStatus);
        }else {
            registrationStatus = new RegistrationStatus();
            userService.register(user);
            //create entry in dynamodb to trigger lambda by sns
            System.out.println("in sns");
            snsService.postToTopic(user.getEmailId(),"POST");

            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        }
    }

    @GetMapping(value = "/healthz")
    public ResponseEntity getHealthz() {
            statsd.increment("Calls - Get healthz");

            return ResponseEntity.status(HttpStatus.OK).body("200 OK");
    }

//    @GetMapping("v1/verifyUserEmail")
//    public ResponseEntity<String> verifedUserUpdate(@RequestParam("email") String email,
//                                                    @RequestParam("token") String token) {
//        String result ="not verfied get";
////        try {
//            System.out.println("in post");
//            //check if token is still valid in EmailID_Data
//
//            // confirm dynamoDB table exists
//
//            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().build();
//            DynamoDB dynamoDB = new DynamoDB(client);
//            logger.info("email is"+email);
//
//            Table userEmailsTable = dynamoDB.getTable("UsernameTokenTable");
//            GetItemSpec spec = new GetItemSpec()
//                    .withPrimaryKey("emailID", email)
//
//                  ;
//            Item item1 = userEmailsTable.getItem(spec);
//            logger.info("first");
//            logger.info(item1.get("emailID").toString());
//
//            Item item = userEmailsTable.getItem("emailID",email);
//            logger.info(item1.get("emailID").toString());
//
//            logger.info("here is issisnipwjdijw");
//          //  Item item = userEmailsTable.getItem(spec);
//            String mail = item.get("emailID").toString();
//            logger.info("email is"+mail);
//            BigDecimal toktime=(BigDecimal)item.get("TimeToLive");
//            logger.info("tokentime: "+toktime);
//
////            lo:q!gger.info(item.get("Token").toString());
//
//        logger.info("here is ff");
//
//
//            if(userEmailsTable == null) {
//                System.out.println("Table 'Emails_DATA' is not in dynamoDB.");
//                return null;
//            }
//
//            updateFields( email,  token);
//            result ="verified success get";
//
//
//            logger.info("here......");
//
//
//        return new ResponseEntity<>(result, HttpStatus.OK);
//    }

    @GetMapping("v1/verifyUserEmail")
    public ResponseEntity<String> verifedUserUpdate(@RequestParam("email") String email,
                                                    @RequestParam("token") String token) {
        String result ="not verfied get";
        try {
            //System.out.println("in post");
            //check if token is still valid in EmailID_Data

            // confirm dynamoDB table exists
            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().build();
            dynamoDB = new DynamoDB(client);
            logger.info("Get /verifyUserEmail");
            Table userEmailsTable = dynamoDB.getTable("UsernameTokenTable");
            if(userEmailsTable == null) {
                logger.info("Table 'UsernameTokenTable' is not in dynamoDB.");
                return null;
            }

            if(email.indexOf(" ", 0)!=-1) {
                email=email.replace(" ", "+");
            }
            Item item = userEmailsTable.getItem("emailID",email);
            logger.info("item= "+item);
            if (item == null ) {
                result="token expired !!!";
            }else {
                BigDecimal tokentime=(BigDecimal)item.get("TimeToLive");

                long now = Instant.now().getEpochSecond(); // unix time
                long timereminsa =  now - tokentime.longValue(); // 2 mins in sec
                logger.info("tokentime: "+tokentime);
                logger.info("now: "+now);
                logger.info("remins: "+timereminsa);
                if(timereminsa > 0)
                {result="token has expired";
                }
                else {
                    result ="verified successfully!!!";
                    updateFields( email,  token);
                }

            }

        }
        catch(Exception e)
        {
            System.out.println(e);
        }

        return new ResponseEntity<>(result, HttpStatus.OK);
    }


    public void updateFields(String email, String token) {

        //check if email has space
        if(email.indexOf(' ', 0)!=-1) {
            email.replace(' ', '+');
        }
        logger.info("Now Email is"+email);

//        Optional<User> tutorialData = Optional.ofNullable(repository.findByEmailId(email));
        User user = repository.findByEmailId(email);
        logger.info("user found"+user.getFname());

        user.setVerified(true);
        user.setVerified_on( OffsetDateTime.now(Clock.systemUTC()).toString());
        user.setAccUpdateTimestamp(Timestamp.from(Instant.now()));
        repository.save(user);
        logger.info("user fields save success");

   }
    //post image
    @PostMapping(value = "/user/self/pic")
    public ResponseEntity<Image> createImage(@RequestParam(value="profilePic", required=true) MultipartFile profilePic, HttpServletRequest request)
            throws Exception {

        System.out.println("In post /user/self/pic");
        statsd.increment("Calls - Post user/self/pic - Post pic of User");

        //statsd.increment("Calls - Post user/self/pic - Post pic of User");
        //check user credentials and get userid
        String upd = request.getHeader("authorization");
        if (upd == null || upd.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String sd = authorizationHeader.replace("Basic ", "");
        byte[] decodedBytes = Base64.getDecoder().decode(sd);
        String decoded = new String(decodedBytes);
        String[] parts = decoded.split(":");
        String emailid = parts[0];
        String password = parts[1];

        //System.println("username: " + userName);
        //System.out.println("password: " + password);


        System.out.println("Setting for post request");
       // multiTenantManager.setCurrentTenant("all");

       // statsd.increment("Calls - find User by username");
       // Optional<User> tutorialData = repository.findByEmailId(emailid);// AndPassword(userName, encodedPass);
       Optional<User>  tutorialData = Optional.ofNullable(repository.findByEmailId(emailid));

        User user = repository.findByEmailId(emailid);

        if (!user.getEmailId().equals(parts[0])){
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);

        }
        Image img=null;
        if (tutorialData.isPresent()) {

            if (bCryptPasswordEncoder.matches(password, tutorialData.get().getPassword())) {
                //matches password complete-- add code here
                if(!tutorialData.get().isVerified()) {
                    System.out.println("User is not yet verified");
                    return new ResponseEntity<>(HttpStatus.FORBIDDEN);
                }

                //check if already image i.e. update request
              //  statsd.increment("Calls - find image by user id");
                Optional<Image> img1 = imageRepository.findByUserId(user.getId());
                if(img1.isPresent())
                {
                    //delete
                    long startTime2 = System.currentTimeMillis();
                    String result = service.deleteFileFromS3Bucket(img1.get().getUrl(), user.getId());
                 //   statsd.increment("Calls - delete image by id");
                    imageRepository.delete(img1.get());

                   // statsd.recordExecutionTime("DB Response Time - Image record delete", System.currentTimeMillis() - startTime2);

                    //System.out.println("previous image deleted");
                }



                String bucket_name =service.uploadFile( user.getId()+"/"+profilePic.getOriginalFilename(), profilePic);

                String url = bucket_name+"/"+ user.getId()+"/"+profilePic.getOriginalFilename();
                img = new Image(user.getId(),profilePic.getOriginalFilename(), url);
                long startTime2 = System.currentTimeMillis();
                imageRepository.save(img);
               // statsd.recordExecutionTime("DB Response Time - Image record saved", System.currentTimeMillis() - startTime2);

               // statsd.recordExecutionTime("Api Response Time - Post user/self/pic - Post pic of user",System.currentTimeMillis() - startTime);

                //return new ResponseEntity<>(tutorialData.get(), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
        } else {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }




        return new ResponseEntity<>(img, HttpStatus.CREATED);
    }




    //get image
    @GetMapping(value = "/user/self/pic")
    public ResponseEntity<Image> getImage(HttpServletRequest request)
            throws Exception {
        System.out.println("In get /user/self/pic");


        statsd.increment("Calls - Get user/self/pic - Get pic of User");

        //check user credentials and get userid
        String upd = request.getHeader("authorization");
        if (upd == null || upd.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String sd = authorizationHeader.replace("Basic ", "");
        byte[] decodedBytes = Base64.getDecoder().decode(sd);
        String decoded = new String(decodedBytes);
        String[] parts = decoded.split(":");
        String emailid = parts[0];
        String password = parts[1];
        // System.out.println("username: " + userName);
        // System.out.println("password: " + password);


        System.out.println("Setting for get request");
        //multiTenantManager.setCurrentTenant("get");

       // statsd.increment("Calls - find User by username");
        Optional<User>  tutorialData = Optional.ofNullable(repository.findByEmailId(emailid));
        Optional<Image> img=null;
        if (tutorialData.isPresent()) {

            if (bCryptPasswordEncoder.matches(password, tutorialData.get().getPassword())) {

                //check if verified user
                //matches password complete-- add code here
                if(!tutorialData.get().isVerified()) {
                    System.out.println("User is not yet verified");
                    return new ResponseEntity<>(HttpStatus.FORBIDDEN);
                }
                User user = tutorialData.get();

                long startTime2 = System.currentTimeMillis();
                //    statsd.increment("Calls - find image by userid");
                img = imageRepository.findByUserId(user.getId());
            //    statsd.recordExecutionTime("DB Response Time - Image record get", System.currentTimeMillis() - startTime2);
                if (img.isPresent()) {
                //    statsd.recordExecutionTime("Api Response Time - Get user/self/pic - Get pic of user",System.currentTimeMillis() - startTime);

                    return new ResponseEntity<>(img.get(), HttpStatus.OK);
                }
                else {
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }
                //return new ResponseEntity<>(tutorialData.get(), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
        } else {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

    }




    //delete image
    @DeleteMapping(value = "/user/self/pic")
    public ResponseEntity<String> deleteImage(HttpServletRequest request)
            throws Exception {
        System.out.println("In delete /user/self/pic");
        long startTime = System.currentTimeMillis();
        statsd.increment("Calls - Delete user/self/pic - Delete pic of User");
        //check user credentials and get userid
        String upd = request.getHeader("authorization");
        if (upd == null || upd.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String sd = authorizationHeader.replace("Basic ", "");
        byte[] decodedBytes = Base64.getDecoder().decode(sd);
        String decoded = new String(decodedBytes);
        String[] parts = decoded.split(":");
        String emailid = parts[0];
        String password = parts[1];

        // System.out.println("username: " + userName);
        // System.out.println("password: " + password);


        System.out.println("Setting for delete request");
    //    multiTenantManager.setCurrentTenant("all");

        Optional<User> tutorialData = Optional.ofNullable(repository.findByEmailId(emailid));// AndPassword(userName, encodedPass);
        Optional<Image> img=null;
        if (tutorialData.isPresent()) {

            if (bCryptPasswordEncoder.matches(password, tutorialData.get().getPassword())) {


                //check if verified user
                if(!tutorialData.get().isVerified()) {
                    System.out.println("User is not yet verified");
                    return new ResponseEntity<>(HttpStatus.FORBIDDEN);
                }


                //matches password complete-- add code here



                User user = tutorialData.get();

                //statsd.increment("Calls - find image by userid");
                img = imageRepository.findByUserId(user.getId());

                if (img.isPresent()) {
                    //so delete

                    String result = service.deleteFileFromS3Bucket(img.get().getUrl(),user.getId());
                    long startTime2 = System.currentTimeMillis();
                    imageRepository.delete(img.get());
                 //   statsd.recordExecutionTime("DB Response Time - Image record delete", System.currentTimeMillis() - startTime2);


                   // statsd.recordExecutionTime("Api Response Time - Delete user/self/pic - Delete pic of user",System.currentTimeMillis() - startTime);
                    return new ResponseEntity<>(result, HttpStatus.OK);
                }
                else {
                    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
                }
                //return new ResponseEntity<>(tutorialData.get(), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

    }
    }



